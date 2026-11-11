package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.application.ApiException;
import com.payflow.application.ErrorCode;
import com.payflow.application.IdempotentExecutor;
import com.payflow.application.IdempotentExecutor.ResponseSpec;
import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyTest extends IntegrationTest {

  @Autowired IdempotentExecutor executor;

  private long ledgerEntries(String paymentId) {
    return jdbc.queryForObject(
        """
        SELECT count(*) FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id
         WHERE t.payment_id = ?::uuid
        """,
        Long.class,
        paymentId);
  }

  private long operations(String paymentId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payment_operations WHERE payment_id = ?::uuid",
        Long.class,
        paymentId);
  }

  @Test
  void missingOrBlankKeyIsRejectedOnEveryMutatingEndpoint() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    List<String> paths =
        List.of(
            "/v1/payments",
            "/v1/payments/" + id + "/captures",
            "/v1/payments/" + id + "/refunds",
            "/v1/payments/" + id + "/voids");
    for (String path : paths) {
      for (String key : new String[] {null, "", "   "}) {
        Response r =
            api.post(
                path,
                merchant,
                key,
                "{\"amount_minor\":1,\"currency\":\"USD\",\"payment_method_token\":\"t\"}");
        assertThat(r.status()).as("%s key=%s", path, key).isEqualTo(400);
        assertThat(r.code()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
      }
    }
    assertThat(api.get("/v1/payments/" + id, merchant).json().path("captured_minor").asLong())
        .isZero();
  }

  @Test
  void overlongOrNonPrintableKeysAreRejected() {
    Api api = api();
    String merchant = newMerchant();
    Response tooLong = api.authorize(merchant, "k".repeat(256), 100, "USD");
    assertThat(tooLong.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    Response spaced = api.authorize(merchant, "has space", 100, "USD");
    assertThat(spaced.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    assertThat(api.authorize(merchant, "k".repeat(255), 100, "USD").status()).isEqualTo(201);
  }

  @Test
  void replayReturnsTheStoredResponseByteForByteWithoutReexecuting() {
    Api api = api();
    String merchant = newMerchant();
    Response first = api.authorize(merchant, "auth-replay", 1_000, "USD");
    Response replayedAuth = api.authorize(merchant, "auth-replay", 1_000, "USD");
    assertThat(replayedAuth.status()).isEqualTo(first.status());
    assertThat(replayedAuth.body()).isEqualTo(first.body());
    assertThat(replayedAuth.header("Idempotency-Replayed")).isEqualTo("true");
    assertThat(first.header("Idempotency-Replayed")).isNull();
    String id = first.json().path("id").asText();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE merchant_id = ?", Long.class, merchant))
        .isEqualTo(1);

    Response capture = api.capture(merchant, "cap-replay", id, 400, "USD");
    long entriesAfterFirst = ledgerEntries(id);
    long opsAfterFirst = operations(id);
    assertThat(entriesAfterFirst).isEqualTo(3);

    for (int i = 0; i < 5; i++) {
      Response replay = api.capture(merchant, "cap-replay", id, 400, "USD");
      assertThat(replay.status()).isEqualTo(201);
      assertThat(replay.body()).isEqualTo(capture.body());
      assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
    }
    assertThat(ledgerEntries(id)).isEqualTo(entriesAfterFirst);
    assertThat(operations(id)).isEqualTo(opsAfterFirst);
    assertThat(api.get("/v1/payments/" + id, merchant).json().path("captured_minor").asLong())
        .isEqualTo(400);
  }

  @Test
  void replayIgnoresJsonFormattingAndKeyOrder() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    Response first =
        api.post(
            "/v1/payments/" + id + "/captures",
            merchant,
            "fmt-key",
            "{\"amount_minor\":100,\"currency\":\"USD\"}");
    Response reordered =
        api.post(
            "/v1/payments/" + id + "/captures",
            merchant,
            "fmt-key",
            "{ \"currency\" : \"USD\",\n   \"amount_minor\" : 100 }");
    assertThat(reordered.status()).isEqualTo(201);
    assertThat(reordered.header("Idempotency-Replayed")).isEqualTo("true");
    assertThat(reordered.body()).isEqualTo(first.body());
  }

  @Test
  void sameKeyWithADifferentPayloadIs422() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    Response first = api.capture(merchant, "reuse-key", id, 100, "USD");
    assertThat(first.status()).isEqualTo(201);

    Response different = api.capture(merchant, "reuse-key", id, 101, "USD");
    assertThat(different.status()).isEqualTo(422);
    assertThat(different.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(different.header("Idempotency-Replayed")).isNull();
    assertThat(api.get("/v1/payments/" + id, merchant).json().path("captured_minor").asLong())
        .isEqualTo(100);
  }

  @Test
  void sameKeyOnADifferentEndpointOrPaymentIs422() {
    Api api = api();
    String merchant = newMerchant();
    String a = api.authorizeOk(merchant, 1_000, "USD");
    String b = api.authorizeOk(merchant, 1_000, "USD");
    assertThat(api.capture(merchant, "shared-key", a, 100, "USD").status()).isEqualTo(201);
    assertThat(api.capture(merchant, "shared-key", b, 100, "USD").code())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(api.refund(merchant, "shared-key", a, 100, "USD").code())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void keysAreScopedPerMerchant() {
    Api api = api();
    String one = newMerchant();
    String two = newMerchant();
    Response a = api.authorize(one, "same-key", 100, "USD");
    Response b = api.authorize(two, "same-key", 999, "USD");
    assertThat(a.status()).isEqualTo(201);
    assertThat(b.status()).isEqualTo(201);
    assertThat(b.header("Idempotency-Replayed")).isNull();
    assertThat(b.json().path("amount_minor").asLong()).isEqualTo(999);
  }

  @Test
  void aClientErrorIsStoredAndReplayedEvenAfterTheStateWouldHaveChanged() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 100, "USD");
    Response rejected = api.capture(merchant, "over-capture", id, 101, "USD");
    assertThat(rejected.status()).isEqualTo(409);
    assertThat(rejected.code()).isEqualTo("AMOUNT_EXCEEDS_CAPTURABLE");

    Response replay = api.capture(merchant, "over-capture", id, 101, "USD");
    assertThat(replay.status()).isEqualTo(409);
    assertThat(replay.body()).isEqualTo(rejected.body());
    assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
    assertThat(replay.header("Content-Type")).startsWith("application/problem+json");

    assertThat(operations(id)).isEqualTo(1);
    assertThat(ledgerEntries(id)).isZero();
    Integer stored =
        jdbc.queryForObject(
            "SELECT response_status FROM idempotency_keys WHERE merchant_id = ? AND idempotency_key = 'over-capture'",
            Integer.class,
            merchant);
    assertThat(stored).isEqualTo(409);
  }

  @Test
  void validationFailuresAreAlsoStoredAndReplayed() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 100, "USD");
    Response bad =
        api.post(
            "/v1/payments/" + id + "/captures", merchant, "bad-body", "{\"currency\":\"USD\"}");
    assertThat(bad.status()).isEqualTo(400);
    Response replay =
        api.post(
            "/v1/payments/" + id + "/captures", merchant, "bad-body", "{\"currency\":\"USD\"}");
    assertThat(replay.status()).isEqualTo(400);
    assertThat(replay.body()).isEqualTo(bad.body());
    assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
  }

  @Test
  void aRequestWhoseKeyIsInFlightGets409WithRetryAfter() throws Exception {
    Api api = api();
    String merchant = newMerchant();
    String key = "in-flight-key";
    String id = api.authorizeOk(merchant, 1_000, "USD");
    String endpoint = "POST /v1/payments/" + id + "/captures";
    byte[] body = Api.money(100, "USD").getBytes();

    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<IdempotentExecutor.StoredResponse> slow =
        CompletableFuture.supplyAsync(
            () ->
                executor.execute(
                    merchant,
                    key,
                    endpoint,
                    body,
                    () -> {
                      holding.countDown();
                      try {
                        release.await(30, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return new ResponseSpec(201, java.util.Map.of("done", true));
                    }));
    assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

    Response collision = api.capture(merchant, key, id, 100, "USD");
    assertThat(collision.status()).isEqualTo(409);
    assertThat(collision.code()).isEqualTo("REQUEST_IN_PROGRESS");
    assertThat(collision.header("Retry-After")).isEqualTo("1");
    assertThat(collision.json().path("retry_after_seconds").asInt()).isEqualTo(1);

    release.countDown();
    IdempotentExecutor.StoredResponse done = slow.get(30, TimeUnit.SECONDS);
    assertThat(done.status()).isEqualTo(201);
    assertThat(done.replayed()).isFalse();

    // After the first request completed, the same call now replays what it stored.
    Response afterwards = api.capture(merchant, key, id, 100, "USD");
    assertThat(afterwards.status()).isEqualTo(201);
    assertThat(afterwards.body()).isEqualTo("{\"done\":true}");
    assertThat(afterwards.header("Idempotency-Replayed")).isEqualTo("true");
  }

  @Test
  void expiredKeysAreTreatedAsAbsentAndPurgedByTheJob() {
    Api api = api();
    String merchant = newMerchant();
    Response first = api.authorize(merchant, "expiring", 100, "USD");
    assertThat(first.status()).isEqualTo(201);
    jdbc.update(
        "UPDATE idempotency_keys SET expires_at = ? WHERE merchant_id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(5)),
        merchant);

    Response second = api.authorize(merchant, "expiring", 100, "USD");
    assertThat(second.status()).isEqualTo(201);
    assertThat(second.header("Idempotency-Replayed")).isNull();
    assertThat(second.json().path("id").asText()).isNotEqualTo(first.json().path("id").asText());

    jdbc.update(
        "UPDATE idempotency_keys SET expires_at = ? WHERE merchant_id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(5)),
        merchant);
    int purged = purger.purgeExpired();
    assertThat(purged).isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE merchant_id = ?",
                Long.class,
                merchant))
        .isZero();
  }

  @Autowired com.payflow.application.IdempotencyPurgeJob purger;

  @Test
  void keyTtlIsStoredFromConfiguration() {
    Api api = api();
    String merchant = newMerchant();
    api.authorize(merchant, "ttl", 100, "USD");
    Instant expires =
        jdbc.queryForObject(
                "SELECT expires_at FROM idempotency_keys WHERE merchant_id = ?",
                java.sql.Timestamp.class,
                merchant)
            .toInstant();
    assertThat(expires)
        .isBetween(Instant.now().plusSeconds(23 * 3600), Instant.now().plusSeconds(25 * 3600));
  }

  @Test
  void executorRefusesMismatchedReuseDirectlyToo() {
    String merchant = newMerchant();
    String key = UUID.randomUUID().toString();
    executor.execute(
        merchant,
        key,
        "POST /x",
        "{\"a\":1}".getBytes(),
        () -> new ResponseSpec(201, java.util.Map.of()));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                executor.execute(
                    merchant,
                    key,
                    "POST /x",
                    "{\"a\":2}".getBytes(),
                    () -> new ResponseSpec(201, java.util.Map.of())))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }
}
