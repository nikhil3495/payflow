package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.payflow.application.WebhookDispatcher;
import com.payflow.application.WebhookSignature;
import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.Concurrently;
import com.payflow.support.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Outbound deliveries against a real local HTTP receiver. */
class OutboundWebhookTest extends IntegrationTest {

  private static final String SIGNING_SECRET = "merchant-signing-secret-0123456789";

  record Received(String eventId, String attempt, String signature, byte[] body) {}

  @Autowired WebhookDispatcher dispatcher;

  private HttpServer receiver;
  private final AtomicInteger responseStatus = new AtomicInteger(200);
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final Map<String, AtomicInteger> perEvent = new ConcurrentHashMap<>();
  private String merchant;

  @BeforeEach
  void startReceiver() throws IOException {
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext(
        "/hook",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          String eventId = exchange.getRequestHeaders().getFirst("PayFlow-Event-Id");
          received.add(
              new Received(
                  eventId,
                  exchange.getRequestHeaders().getFirst("PayFlow-Delivery-Attempt"),
                  exchange.getRequestHeaders().getFirst("PayFlow-Signature"),
                  body));
          perEvent.computeIfAbsent(eventId, k -> new AtomicInteger()).incrementAndGet();
          exchange.sendResponseHeaders(responseStatus.get(), -1);
          exchange.close();
        });
    receiver.start();
    merchant = newMerchant();
    Response registered =
        api()
            .send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/v1/webhook-endpoint"))
                    .header("Content-Type", "application/json")
                    .PUT(
                        java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"url\": \"http://127.0.0.1:%d/hook\", \"signing_secret\": \"%s\"}"
                                .formatted(receiver.getAddress().getPort(), SIGNING_SECRET))),
                merchant,
                newKey(),
                null);
    assertThat(registered.status()).isEqualTo(201);
  }

  @AfterEach
  void stopReceiver() {
    receiver.stop(0);
    // Park anything this test left pending so a later test's dispatcher run cannot pick it up.
    jdbc.update(
        "UPDATE webhook_deliveries SET next_attempt_at = now() + interval '30 days' WHERE merchant_id = ? AND state = 'PENDING'",
        merchant);
  }

  private void makeDue() {
    jdbc.update(
        "UPDATE webhook_deliveries SET next_attempt_at = now() - interval '1 second' WHERE merchant_id = ? AND state = 'PENDING'",
        merchant);
  }

  private Map<String, Object> onlyDelivery() {
    return jdbc.queryForMap("SELECT * FROM webhook_deliveries WHERE merchant_id = ?", merchant);
  }

  @Test
  void aCommittedPaymentChangeIsDeliveredSignedAndMarkedDelivered() {
    Api api = api();
    String id = api.authorizeOk(merchant, 1_000, "USD");

    assertThat(onlyDelivery().get("state")).isEqualTo("PENDING");
    assertThat(dispatcher.processDue()).isGreaterThanOrEqualTo(1);

    assertThat(received).hasSize(1);
    Received delivery = received.get(0);
    WebhookSignature.verify(
        SIGNING_SECRET.getBytes(StandardCharsets.UTF_8),
        delivery.signature(),
        delivery.body(),
        Instant.now(),
        Duration.ofMinutes(5));
    assertThat(delivery.attempt()).isEqualTo("1");
    JsonNode event = parse(delivery.body());
    assertThat(event.path("type").asText()).isEqualTo("payment.authorized");
    assertThat(event.path("id").asText()).isEqualTo(delivery.eventId());
    assertThat(event.path("data").path("id").asText()).isEqualTo(id);
    assertThat(event.path("data").path("authorized_minor").asLong()).isEqualTo(1_000);
    assertThat(onlyDelivery().get("state")).isEqualTo("DELIVERED");
    assertThat(onlyDelivery().get("attempt_count")).isEqualTo(1);
  }

  private static JsonNode parse(byte[] body) {
    try {
      return Api.JSON.readTree(body);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void everyPaymentOperationEmitsItsEventInOrderOfCreation() {
    Api api = api();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    api.capture(merchant, newKey(), id, 400, "USD");
    api.refund(merchant, newKey(), id, 100, "USD");
    api.voidPayment(merchant, newKey(), id);

    List<String> types =
        jdbc.queryForList(
            "SELECT event_type FROM webhook_deliveries WHERE merchant_id = ? ORDER BY created_at, id",
            String.class,
            merchant);
    assertThat(types)
        .containsExactly(
            "payment.authorized", "payment.captured", "payment.refunded", "payment.voided");
  }

  @Test
  void aRolledBackOperationLeavesNoEvent() {
    Api api = api();
    String id = api.authorizeOk(merchant, 100, "USD");
    api.capture(merchant, newKey(), id, 101, "USD"); // 409: rejected, nothing committed
    api.refund(merchant, newKey(), id, 1, "USD"); // 409
    Long events =
        jdbc.queryForObject(
            "SELECT count(*) FROM webhook_deliveries WHERE merchant_id = ?", Long.class, merchant);
    assertThat(events).isEqualTo(1); // only the authorization
  }

  @Test
  void aReplayedRequestDoesNotEmitASecondEvent() {
    Api api = api();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    api.capture(merchant, "cap-once", id, 100, "USD");
    api.capture(merchant, "cap-once", id, 100, "USD");
    Long events =
        jdbc.queryForObject(
            "SELECT count(*) FROM webhook_deliveries WHERE merchant_id = ? AND event_type = 'payment.captured'",
            Long.class,
            merchant);
    assertThat(events).isEqualTo(1);
  }

  @Test
  void failuresAreRetriedWithBackoffThenDeadLettered() {
    Api api = api();
    api.authorizeOk(merchant, 1_000, "USD");
    responseStatus.set(500);

    // Attempt 1 and 2 fail and are rescheduled inside their full-jitter windows.
    for (int attempt = 1; attempt <= 2; attempt++) {
      makeDue();
      Instant before = Instant.now();
      dispatcher.processDue();
      Map<String, Object> row = onlyDelivery();
      assertThat(row.get("state")).isEqualTo("PENDING");
      assertThat(row.get("attempt_count")).isEqualTo(attempt);
      assertThat(row.get("last_status")).isEqualTo(500);
      Instant next = ((java.sql.Timestamp) row.get("next_attempt_at")).toInstant();
      Duration ceiling = Duration.ofSeconds(30L << (attempt - 1));
      assertThat(next)
          .isBetween(before.minusSeconds(1), Instant.now().plus(ceiling).plusSeconds(1));
    }
    assertThat(received).hasSize(2);

    // The configured budget in the test profile is 3 attempts: the third lands in the DLQ.
    makeDue();
    dispatcher.processDue();
    Map<String, Object> dead = onlyDelivery();
    assertThat(dead.get("state")).isEqualTo("DEAD");
    assertThat(dead.get("attempt_count")).isEqualTo(3);
    Map<String, Object> dlq =
        jdbc.queryForMap("SELECT * FROM webhook_dead_letter WHERE merchant_id = ?", merchant);
    assertThat(dlq.get("delivery_id").toString()).isEqualTo(dead.get("id").toString());
    assertThat(dlq.get("attempt_count")).isEqualTo(3);
    assertThat(dlq.get("last_status")).isEqualTo(500);
    assertThat(dlq.get("event_type")).isEqualTo("payment.authorized");
    assertThat((String) dlq.get("payload")).contains("\"payment.authorized\"");

    // A dead delivery is never attempted again.
    makeDue();
    dispatcher.processDue();
    assertThat(received).hasSize(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM webhook_dead_letter WHERE merchant_id = ?",
                Long.class,
                merchant))
        .isEqualTo(1);
  }

  @Test
  void aDeliveryThatRecoversStopsRetrying() {
    api().authorizeOk(merchant, 1_000, "USD");
    responseStatus.set(503);
    makeDue();
    dispatcher.processDue();
    assertThat(onlyDelivery().get("state")).isEqualTo("PENDING");

    responseStatus.set(204);
    makeDue();
    dispatcher.processDue();
    assertThat(onlyDelivery().get("state")).isEqualTo("DELIVERED");
    assertThat(onlyDelivery().get("attempt_count")).isEqualTo(2);
    assertThat(received).extracting(Received::attempt).containsExactly("1", "2");
    assertThat(received.get(0).eventId()).isEqualTo(received.get(1).eventId());
  }

  @Test
  void anUnreachableEndpointCountsAsAFailedAttempt() {
    api().authorizeOk(merchant, 1_000, "USD");
    receiver.stop(0);
    makeDue();
    dispatcher.processDue();
    Map<String, Object> row = onlyDelivery();
    assertThat(row.get("state")).isEqualTo("PENDING");
    assertThat(row.get("attempt_count")).isEqualTo(1);
    assertThat((String) row.get("last_error")).isNotBlank();
  }

  @Test
  void claimedDeliveriesAreNotRepeatedWhileLeasedAndDispatchersNeverDoubleSend() {
    Api api = api();
    for (int i = 0; i < 12; i++) {
      api.authorizeOk(merchant, 100 + i, "USD");
    }
    // Four dispatchers racing over the same due rows (FOR UPDATE SKIP LOCKED + lease).
    Concurrently.run(4, 4, i -> dispatcher.processDue());

    assertThat(perEvent).hasSize(12);
    assertThat(perEvent.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM webhook_deliveries WHERE merchant_id = ? AND state = 'DELIVERED'",
                Long.class,
                merchant))
        .isEqualTo(12);
  }

  @Test
  void aDeliveryThatIsNotYetDueIsNotAttempted() {
    api().authorizeOk(merchant, 100, "USD");
    responseStatus.set(500);
    makeDue();
    dispatcher.processDue(); // fails; rescheduled by the (random) backoff
    assertThat(received).hasSize(1);
    // Pin the schedule far in the future so the assertion does not depend on the jitter draw.
    jdbc.update(
        "UPDATE webhook_deliveries SET next_attempt_at = now() + interval '1 hour' WHERE merchant_id = ?",
        merchant);

    dispatcher.processDue();

    assertThat(received).hasSize(1);
    assertThat(onlyDelivery().get("attempt_count")).isEqualTo(1);
  }

  @Test
  void aClaimedDeliveryIsLeasedSoItIsNotClaimedAgainWhileInFlight() {
    api().authorizeOk(merchant, 100, "USD");
    makeDue();
    // Claim directly, as a dispatcher that then dies mid-delivery would have done.
    java.time.Instant now = java.time.Instant.now();
    var repository =
        new com.payflow.infrastructure.WebhookRepository(
            org.springframework.jdbc.core.simple.JdbcClient.create(jdbc));
    var first = repository.claimDue(now, 100, now.plusSeconds(120));
    var second = repository.claimDue(now, 100, now.plusSeconds(120));
    assertThat(first).extracting(d -> d.merchantId()).contains(merchant);
    assertThat(second).extracting(d -> d.merchantId()).doesNotContain(merchant);

    // Once the lease expires the row becomes due again (at-least-once after a crash).
    var afterLease = repository.claimDue(now.plusSeconds(121), 100, now.plusSeconds(300));
    assertThat(afterLease).extracting(d -> d.merchantId()).contains(merchant);
  }

  @Test
  void endpointRegistrationValidatesTheUrlAndSecretAndIsIdempotent() {
    Api api = api();
    String other = newMerchant();
    for (String badUrl :
        new String[] {"ftp://example.com/x", "not a url", "https://user:pw@203.0.113.10/x"}) {
      Response r =
          putEndpoint(
              api,
              other,
              newKey(),
              "{\"url\": \"%s\", \"signing_secret\": \"%s\"}".formatted(badUrl, SIGNING_SECRET));
      assertThat(r.status()).as(badUrl).isEqualTo(400);
      assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }
    Response shortSecret =
        putEndpoint(
            api,
            other,
            newKey(),
            "{\"url\": \"http://127.0.0.1:9/x\", \"signing_secret\": \"short\"}");
    assertThat(shortSecret.status()).isEqualTo(400);
    assertThat(putEndpoint(api, other, newKey(), "{\"url\": \"http://127.0.0.1:9/x\"}").status())
        .isEqualTo(400);

    String body =
        "{\"url\": \"http://127.0.0.1:9/x\", \"signing_secret\": \"%s\"}".formatted(SIGNING_SECRET);
    Response created = putEndpoint(api, other, "ep-key", body);
    Response replay = putEndpoint(api, other, "ep-key", body);
    assertThat(created.status()).isEqualTo(201);
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
    assertThat(created.body()).doesNotContain(SIGNING_SECRET);

    Response updated = putEndpoint(api, other, newKey(), body.replace(":9/x", ":10/x"));
    assertThat(updated.status()).isEqualTo(200);
    assertThat(putEndpoint(api, other, "", body).code()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
  }

  private Response putEndpoint(Api api, String merchantId, String key, String body) {
    return api.send(
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/v1/webhook-endpoint"))
            .header("Content-Type", "application/json")
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)),
        merchantId,
        key,
        null);
  }
}
