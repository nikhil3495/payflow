package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.application.UnmatchedEventSweepJob;
import com.payflow.application.WebhookSignature;
import com.payflow.support.Concurrently;
import com.payflow.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A PSP event can reach us before the transaction that created its operation commits. The PSP got a
 * 200, so it will not re-send; the sweeper is what makes the event take effect anyway.
 */
class UnmatchedEventSweepJobTest extends IntegrationTest {

  private static final byte[] SECRET =
      "test-psp-webhook-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  @Autowired UnmatchedEventSweepJob sweeper;
  @Autowired MeterRegistry meters;

  private String send(String body) {
    byte[] raw = body.getBytes(StandardCharsets.UTF_8);
    return api()
        .postRaw(
            "/v1/webhooks/psp",
            Map.of(
                "Content-Type",
                "application/json",
                "PSP-Signature",
                WebhookSignature.header(SECRET, Instant.now(), raw)),
            raw)
        .json()
        .path("status")
        .asText();
  }

  private static String event(String eventId, String type, String reference) {
    return "{\"id\": \"%s\", \"type\": \"%s\", \"data\": {\"psp_reference\": \"%s\"}}"
        .formatted(eventId, type, reference);
  }

  /** Creates a payment and returns the psp reference of its operation. */
  private String existingOperationReference(String merchant) {
    String id = api().authorizeOk(merchant, 1_000, "USD");
    return jdbc.queryForObject(
        "SELECT psp_reference FROM payment_operations WHERE payment_id = ?::uuid",
        String.class,
        id);
  }

  private void commitOperationUnder(String existingReference, String reference) {
    jdbc.update(
        "UPDATE payment_operations SET psp_reference = ? WHERE psp_reference = ?",
        reference,
        existingReference);
  }

  private String pspStatus(String reference) {
    return jdbc.queryForObject(
        "SELECT psp_status FROM payment_operations WHERE psp_reference = ?",
        String.class,
        reference);
  }

  private String eventState(String eventId) {
    return jdbc.queryForObject(
        "SELECT processing_state FROM webhook_events WHERE psp_event_id = ?",
        String.class,
        eventId);
  }

  private static String lateReference() {
    return "psp_auth_late_" + UUID.randomUUID().toString().replace("-", "");
  }

  @Test
  void anEventThatBeatTheCommitTakesEffectWithoutAnyRedelivery() {
    String late = lateReference();
    String eventId = "evt_" + UUID.randomUUID();
    String existing = existingOperationReference(newMerchant());

    assertThat(send(event(eventId, "operation.failed", late))).isEqualTo("unmatched");
    commitOperationUnder(existing, late);
    assertThat(pspStatus(late)).isEqualTo("PENDING");

    // The PSP never re-sends. Only the sweeper runs.
    sweeper.sweep();

    assertThat(pspStatus(late)).isEqualTo("FAILED");
    assertThat(eventState(eventId)).isEqualTo("PROCESSED");

    // Applied exactly once: a further sweep does nothing and a late re-send is a duplicate.
    assertThat(sweeper.sweep().applied()).isZero();
    assertThat(send(event(eventId, "operation.failed", late))).isEqualTo("duplicate");
  }

  @Test
  void aSweepRacingARedeliveryAppliesTheEventExactlyOnce() {
    String late = lateReference();
    String eventId = "evt_" + UUID.randomUUID();
    String existing = existingOperationReference(newMerchant());
    send(event(eventId, "operation.confirmed", late));
    commitOperationUnder(existing, late);

    List<String> outcomes =
        Concurrently.run(
                8,
                8,
                i -> i % 2 == 0 ? send(event(eventId, "operation.confirmed", late)) : sweepOnce())
            .stream()
            .map(t -> t.value())
            .toList();

    assertThat(pspStatus(late)).isEqualTo("CONFIRMED");
    assertThat(eventState(eventId)).isEqualTo("PROCESSED");
    assertThat(outcomes.stream().filter(s -> s.equals("processed") || s.equals("applied")).count())
        .isEqualTo(1);
  }

  private String sweepOnce() {
    return sweeper.sweep().applied() > 0 ? "applied" : "none";
  }

  @Test
  void anEventWhoseOperationNeverAppearsAgesOutVisiblyAndIsNeverResurrected() {
    String late = lateReference();
    String eventId = "evt_" + UUID.randomUUID();
    String existing = existingOperationReference(newMerchant());
    send(event(eventId, "operation.failed", late));

    // Still young: left alone, still retryable.
    sweeper.sweep();
    assertThat(eventState(eventId)).isEqualTo("UNMATCHED");

    // Past the retention window: abandoned, loudly, not silently dropped.
    double before = meters.counter("payflow.webhook.inbound.abandoned").count();
    jdbc.update(
        "UPDATE webhook_events SET received_at = now() - interval '25 hours' WHERE psp_event_id = ?",
        eventId);
    assertThat(sweeper.sweep().abandoned()).isEqualTo(1);
    assertThat(eventState(eventId)).isEqualTo("ABANDONED");
    assertThat(meters.counter("payflow.webhook.inbound.abandoned").count() - before).isEqualTo(1);

    // The operation turns up much later: the abandoned event must not be applied.
    commitOperationUnder(existing, late);
    sweeper.sweep();
    assertThat(send(event(eventId, "operation.failed", late))).isEqualTo("duplicate");
    assertThat(pspStatus(late)).isEqualTo("PENDING");
  }
}
