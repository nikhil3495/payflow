package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.application.UnmatchedEventSweepJob;
import com.payflow.application.WebhookSignature;
import com.payflow.support.Api.Response;
import com.payflow.support.Concurrently;
import com.payflow.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** An operator can replay an event the sweeper gave up on, once its operation exists. */
class AbandonedEventReplayTest extends IntegrationTest {

  private static final String ADMIN_TOKEN = "test-admin-token-0123456789abcdef";
  private static final byte[] SECRET =
      "test-psp-webhook-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  @Autowired UnmatchedEventSweepJob sweeper;

  private Response replay(String eventId, String token) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(
            URI.create(
                "http://localhost:" + port + "/v1/admin/inbound-events/" + eventId + "/replay"));
    return api()
        .send(
            builder.POST(HttpRequest.BodyPublishers.noBody()),
            null,
            null,
            token == null ? Map.of() : Map.of("X-Admin-Token", token));
  }

  private Response list(String query) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/v1/admin/inbound-events" + query));
    return api().send(builder.GET(), null, null, Map.of("X-Admin-Token", ADMIN_TOKEN));
  }

  /** A fixture: an event that arrived early and has been abandoned. Returns {eventId, ref, old}. */
  private String[] abandonedEvent() {
    String merchant = newMerchant();
    String paymentId = api().authorizeOk(merchant, 1_000, "USD");
    String existing =
        jdbc.queryForObject(
            "SELECT psp_reference FROM payment_operations WHERE payment_id = ?::uuid",
            String.class,
            paymentId);
    String late = "psp_auth_late_" + UUID.randomUUID().toString().replace("-", "");
    String eventId = "evt_" + UUID.randomUUID();
    String body =
        "{\"id\": \"%s\", \"type\": \"operation.failed\", \"data\": {\"psp_reference\": \"%s\"}}"
            .formatted(eventId, late);
    byte[] raw = body.getBytes(StandardCharsets.UTF_8);
    api()
        .postRaw(
            "/v1/webhooks/psp",
            Map.of(
                "Content-Type",
                "application/json",
                "PSP-Signature",
                WebhookSignature.header(SECRET, Instant.now(), raw)),
            raw);
    jdbc.update(
        "UPDATE webhook_events SET received_at = now() - interval '25 hours' WHERE psp_event_id = ?",
        eventId);
    assertThat(sweeper.sweep().abandoned()).isGreaterThanOrEqualTo(1);
    assertThat(eventState(eventId)).isEqualTo("ABANDONED");
    return new String[] {eventId, late, existing};
  }

  private void operationAppears(String[] fixture) {
    jdbc.update(
        "UPDATE payment_operations SET psp_reference = ? WHERE psp_reference = ?",
        fixture[1],
        fixture[2]);
  }

  private String eventState(String eventId) {
    return jdbc.queryForObject(
        "SELECT processing_state FROM webhook_events WHERE psp_event_id = ?",
        String.class,
        eventId);
  }

  private String pspStatus(String reference) {
    return jdbc.queryForObject(
        "SELECT psp_status FROM payment_operations WHERE psp_reference = ?",
        String.class,
        reference);
  }

  @Test
  void replayAppliesAnAbandonedEventOnceItsOperationExistsAndIsSafeTwice() {
    String[] f = abandonedEvent();
    operationAppears(f);
    assertThat(pspStatus(f[1])).isEqualTo("PENDING");

    assertThat(replay(f[0], null).status()).isEqualTo(401);
    assertThat(replay(f[0], "wrong-token-wrong-token-wrong").status()).isEqualTo(401);
    assertThat(pspStatus(f[1])).isEqualTo("PENDING");

    Response first = replay(f[0], ADMIN_TOKEN);
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.json().path("result").asText()).isEqualTo("replayed");
    assertThat(pspStatus(f[1])).isEqualTo("FAILED");
    assertThat(eventState(f[0])).isEqualTo("PROCESSED");
    assertThat(
            jdbc.queryForObject(
                "SELECT replayed_at IS NOT NULL FROM webhook_events WHERE psp_event_id = ?",
                Boolean.class,
                f[0]))
        .isTrue();

    Response second = replay(f[0], ADMIN_TOKEN);
    assertThat(second.status()).isEqualTo(200);
    assertThat(second.json().path("result").asText()).isEqualTo("already_processed");
  }

  @Test
  void replayRefusesWhileTheOperationStillDoesNotExistAndWorksLater() {
    String[] f = abandonedEvent();

    Response refused = replay(f[0], ADMIN_TOKEN);
    assertThat(refused.status()).isEqualTo(409);
    assertThat(refused.code()).isEqualTo("EVENT_NOT_REPLAYABLE");
    assertThat(eventState(f[0])).isEqualTo("ABANDONED");
    assertThat(pspStatus(f[2])).isEqualTo("PENDING");

    operationAppears(f);
    assertThat(replay(f[0], ADMIN_TOKEN).status()).isEqualTo(200);
    assertThat(pspStatus(f[1])).isEqualTo("FAILED");
  }

  @Test
  void unknownAndNotAbandonedEventsAreNotReplayable() {
    assertThat(replay("evt_" + UUID.randomUUID(), ADMIN_TOKEN).status()).isEqualTo(404);

    String[] f = abandonedEvent();
    jdbc.update(
        "UPDATE webhook_events SET processing_state = 'UNMATCHED' WHERE psp_event_id = ?", f[0]);
    assertThat(replay(f[0], ADMIN_TOKEN).status()).isEqualTo(409);
  }

  @Test
  void concurrentReplaysApplyTheEventExactlyOnce() {
    String[] f = abandonedEvent();
    operationAppears(f);

    List<String> results =
        Concurrently.run(6, 6, i -> replay(f[0], ADMIN_TOKEN).json().path("result").asText())
            .stream()
            .map(t -> t.value())
            .toList();

    assertThat(results.stream().filter("replayed"::equals).count()).isEqualTo(1);
    assertThat(results.stream().filter("already_processed"::equals).count()).isEqualTo(5);
    assertThat(pspStatus(f[1])).isEqualTo("FAILED");
  }

  @Test
  void abandonedEventsCanBeListedByAnOperator() {
    String[] f = abandonedEvent();

    assertThat(list("?state=ABANDONED").status()).isEqualTo(200);
    assertThat(list("?state=ABANDONED").json().toString()).contains(f[0]);
    assertThat(list("?state=BOGUS").status()).isEqualTo(400);
  }
}
