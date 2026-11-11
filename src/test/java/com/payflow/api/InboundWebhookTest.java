package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.application.WebhookSignature;
import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboundWebhookTest extends IntegrationTest {

  private static final byte[] SECRET =
      "test-psp-webhook-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  private Response send(byte[] body, String signatureHeader) {
    Map<String, String> headers =
        signatureHeader == null
            ? Map.of("Content-Type", "application/json")
            : Map.of("Content-Type", "application/json", "PSP-Signature", signatureHeader);
    return api().postRaw("/v1/webhooks/psp", headers, body);
  }

  private Response sendSigned(String body) {
    byte[] raw = body.getBytes(StandardCharsets.UTF_8);
    return send(raw, WebhookSignature.header(SECRET, Instant.now(), raw));
  }

  private String event(String eventId, String type, String reference) {
    return "{\"id\": \"%s\", \"type\": \"%s\", \"data\": {\"psp_reference\": \"%s\"}}"
        .formatted(eventId, type, reference);
  }

  private String captureReference(Api api, String merchant) {
    String id = api.authorizeOk(merchant, 1_000, "USD");
    api.capture(merchant, newKey(), id, 1_000, "USD");
    return jdbc.queryForObject(
        "SELECT psp_reference FROM payment_operations WHERE payment_id = ?::uuid AND type = 'CAPTURE'",
        String.class,
        id);
  }

  private String pspStatus(String reference) {
    return jdbc.queryForObject(
        "SELECT psp_status FROM payment_operations WHERE psp_reference = ?",
        String.class,
        reference);
  }

  private long eventCount(String eventId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM webhook_events WHERE psp_event_id = ?", Long.class, eventId);
  }

  @Test
  void aValidSignatureIsProcessedAndUpdatesTheOperation() {
    String reference = captureReference(api(), newMerchant());
    assertThat(pspStatus(reference)).isEqualTo("PENDING");

    String eventId = "evt_" + UUID.randomUUID();
    Response r = sendSigned(event(eventId, "operation.confirmed", reference));

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.json().path("status").asText()).isEqualTo("processed");
    assertThat(pspStatus(reference)).isEqualTo("CONFIRMED");
    assertThat(eventCount(eventId)).isEqualTo(1);
  }

  @Test
  void theSignatureCoversTheRawBytesNotAReserializedObject() {
    String reference = captureReference(api(), newMerchant());
    String eventId = "evt_" + UUID.randomUUID();
    // Odd whitespace and key order: valid JSON whose bytes differ from any canonical form.
    String raw =
        "{ \"data\" :{\"psp_reference\":\"%s\"},\n\t\"type\":  \"operation.failed\" , \"id\":\"%s\" }"
            .formatted(reference, eventId);
    Response ok = sendSigned(raw);
    assertThat(ok.status()).isEqualTo(200);
    assertThat(pspStatus(reference)).isEqualTo("FAILED");

    // The same event signed over a compacted re-serialization does not verify against the raw
    // bytes.
    String eventId2 = "evt_" + UUID.randomUUID();
    String raw2 =
        "{ \"id\" : \"%s\", \"type\":\"operation.confirmed\", \"data\":{\"psp_reference\":\"%s\"} }"
            .formatted(eventId2, reference);
    String compacted = raw2.replaceAll("\\s+", "");
    byte[] rawBytes = raw2.getBytes(StandardCharsets.UTF_8);
    Response bad =
        send(
            rawBytes,
            WebhookSignature.header(
                SECRET, Instant.now(), compacted.getBytes(StandardCharsets.UTF_8)));
    assertThat(bad.status()).isEqualTo(401);
    assertThat(eventCount(eventId2)).isZero();
  }

  @Test
  void aTamperedBodyIsRejectedWithNoStateChange() {
    String reference = captureReference(api(), newMerchant());
    String eventId = "evt_" + UUID.randomUUID();
    byte[] original =
        event(eventId, "operation.confirmed", reference).getBytes(StandardCharsets.UTF_8);
    String header = WebhookSignature.header(SECRET, Instant.now(), original);
    byte[] tampered =
        event(eventId, "operation.failed", reference).getBytes(StandardCharsets.UTF_8);

    Response r = send(tampered, header);

    assertThat(r.status()).isEqualTo(401);
    assertThat(r.code()).isEqualTo("INVALID_WEBHOOK_SIGNATURE");
    assertThat(r.body()).doesNotContain("SIGNATURE_MISMATCH");
    assertThat(pspStatus(reference)).isEqualTo("PENDING");
    assertThat(eventCount(eventId)).isZero();
  }

  @Test
  void aTamperedSignatureIsRejected() {
    String reference = captureReference(api(), newMerchant());
    String eventId = "evt_" + UUID.randomUUID();
    byte[] body = event(eventId, "operation.confirmed", reference).getBytes(StandardCharsets.UTF_8);
    String header = WebhookSignature.header(SECRET, Instant.now(), body);
    String tampered = header.substring(0, header.length() - 1) + (header.endsWith("0") ? "1" : "0");

    assertThat(send(body, tampered).status()).isEqualTo(401);
    assertThat(send(body, "t=1,v1=00").status()).isEqualTo(401);
    assertThat(send(body, null).status()).isEqualTo(401);
    assertThat(pspStatus(reference)).isEqualTo("PENDING");
    assertThat(eventCount(eventId)).isZero();
  }

  @Test
  void aWrongSecretIsRejected() {
    byte[] body =
        event("evt_" + UUID.randomUUID(), "operation.confirmed", "x")
            .getBytes(StandardCharsets.UTF_8);
    byte[] otherSecret = "some-other-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    assertThat(send(body, WebhookSignature.header(otherSecret, Instant.now(), body)).status())
        .isEqualTo(401);
  }

  @Test
  void anExpiredOrFutureTimestampIsRejectedEvenWithAGoodSignature() {
    String reference = captureReference(api(), newMerchant());
    String eventId = "evt_" + UUID.randomUUID();
    byte[] body = event(eventId, "operation.confirmed", reference).getBytes(StandardCharsets.UTF_8);

    Response old =
        send(body, WebhookSignature.header(SECRET, Instant.now().minusSeconds(3_600), body));
    Response future =
        send(body, WebhookSignature.header(SECRET, Instant.now().plusSeconds(3_600), body));

    assertThat(old.status()).isEqualTo(401);
    assertThat(future.status()).isEqualTo(401);
    assertThat(pspStatus(reference)).isEqualTo("PENDING");
    assertThat(eventCount(eventId)).isZero();
  }

  @Test
  void aReplayedEventIdIsAcknowledgedAndChangesNothing() {
    String reference = captureReference(api(), newMerchant());
    String eventId = "evt_" + UUID.randomUUID();

    Response first = sendSigned(event(eventId, "operation.confirmed", reference));
    assertThat(first.json().path("status").asText()).isEqualTo("processed");
    assertThat(pspStatus(reference)).isEqualTo("CONFIRMED");

    // Same event id, different (validly signed) content: still a duplicate, still a no-op.
    Response replay = sendSigned(event(eventId, "operation.failed", reference));
    assertThat(replay.status()).isEqualTo(200);
    assertThat(replay.json().path("status").asText()).isEqualTo("duplicate");
    assertThat(pspStatus(reference)).isEqualTo("CONFIRMED");
    assertThat(eventCount(eventId)).isEqualTo(1);
  }

  @Test
  void concurrentDuplicatesOfOneEventApplyExactlyOnce() throws Exception {
    String reference = captureReference(api(), newMerchant());
    String eventId = "evt_" + UUID.randomUUID();
    String body = event(eventId, "operation.confirmed", reference);

    var results = com.payflow.support.Concurrently.run(20, 20, i -> sendSigned(body));

    long processed =
        results.stream()
            .filter(t -> t.value().json().path("status").asText().equals("processed"))
            .count();
    long duplicates =
        results.stream()
            .filter(t -> t.value().json().path("status").asText().equals("duplicate"))
            .count();
    assertThat(processed).isEqualTo(1);
    assertThat(duplicates).isEqualTo(19);
    assertThat(eventCount(eventId)).isEqualTo(1);
  }

  @Test
  void unknownTypesAreIgnoredAndUnknownReferencesAreRecordedAsUnmatched() {
    String ignoredId = "evt_" + UUID.randomUUID();
    assertThat(sendSigned(event(ignoredId, "dispute.created", "x")).json().path("status").asText())
        .isEqualTo("ignored");
    String unmatchedId = "evt_" + UUID.randomUUID();
    assertThat(
            sendSigned(event(unmatchedId, "operation.confirmed", "psp_nothing"))
                .json()
                .path("status")
                .asText())
        .isEqualTo("unmatched");
    assertThat(
            jdbc.queryForObject(
                "SELECT processing_state FROM webhook_events WHERE psp_event_id = ?",
                String.class,
                unmatchedId))
        .isEqualTo("UNMATCHED");
  }

  @Test
  void aValidlySignedButMalformedEventIsA400() {
    assertThat(sendSigned("not json").status()).isEqualTo(400);
    assertThat(sendSigned("[]").status()).isEqualTo(400);
    Response noId = sendSigned("{\"type\": \"operation.confirmed\"}");
    assertThat(noId.status()).isEqualTo(400);
    assertThat(noId.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(sendSigned("{\"id\": 5, \"type\": \"x\"}").status()).isEqualTo(400);
    assertThat(sendSigned("{\"id\": \"" + "e".repeat(256) + "\", \"type\": \"x\"}").status())
        .isEqualTo(400);
  }
}
