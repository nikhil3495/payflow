package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.Concurrently;
import com.payflow.support.Concurrently.Timed;
import com.payflow.support.IntegrationTest;
import com.payflow.support.LedgerAssertions;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Races on a single payment from a real thread pool against real PostgreSQL. Each invariant must
 * hold under concurrency, not only single-threaded.
 */
class ConcurrencyTest extends IntegrationTest {

  private static Map<Integer, Long> distribution(List<Timed<Response>> results) {
    return results.stream()
        .collect(
            Collectors.groupingBy(t -> t.value().status(), TreeMap::new, Collectors.counting()));
  }

  private long captured(Api api, String merchant, String id) {
    return api.get("/v1/payments/" + id, merchant).json().path("captured_minor").asLong();
  }

  @Test
  void concurrentPartialCapturesNeverExceedTheAuthorization() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");

    List<Timed<Response>> results =
        Concurrently.run(60, 30, i -> api.capture(merchant, newKey(), id, 30, "USD"));

    Map<Integer, Long> counts = distribution(results);
    assertThat(counts.keySet()).isSubsetOf(201, 409);
    assertThat(counts.get(201))
        .isEqualTo(33L); // 33 x 30 = 990; the remaining 10 cannot fit another 30
    assertThat(captured(api, merchant, id)).isEqualTo(990);
    LedgerAssertions.assertConsistent(jdbc, id);
  }

  @Test
  void concurrentRefundsNeverExceedWhatWasCaptured() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    api.capture(merchant, newKey(), id, 1_000, "USD");

    List<Timed<Response>> results =
        Concurrently.run(40, 20, i -> api.refund(merchant, newKey(), id, 50, "USD"));

    Map<Integer, Long> counts = distribution(results);
    assertThat(counts.keySet()).isSubsetOf(201, 409);
    assertThat(counts.get(201)).isEqualTo(20L);
    Response payment = api.get("/v1/payments/" + id, merchant);
    assertThat(payment.json().path("refunded_minor").asLong()).isEqualTo(1_000);
    assertThat(payment.json().path("state").asText()).isEqualTo("REFUNDED");
    LedgerAssertions.assertConsistent(jdbc, id);
  }

  @Test
  void concurrentDoubleVoidSucceedsExactlyOnce() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");

    List<Timed<Response>> results =
        Concurrently.run(25, 25, i -> api.voidPayment(merchant, newKey(), id));

    Map<Integer, Long> counts = distribution(results);
    assertThat(counts.keySet()).isSubsetOf(201, 409);
    assertThat(counts.get(201)).isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment_operations WHERE payment_id = ?::uuid AND type = 'VOID'",
                Long.class,
                id))
        .isEqualTo(1);
    LedgerAssertions.assertConsistent(jdbc, id);
  }

  @Test
  void captureAndVoidRacingEachOtherNeverBothWin() {
    Api api = api();
    String merchant = newMerchant();
    for (int round = 0; round < 15; round++) {
      String id = api.authorizeOk(merchant, 500, "USD");
      List<Timed<Response>> results =
          Concurrently.run(
              8,
              8,
              i ->
                  i % 2 == 0
                      ? api.capture(merchant, newKey(), id, 500, "USD")
                      : api.voidPayment(merchant, newKey(), id));
      assertThat(distribution(results).keySet()).isSubsetOf(201, 409);
      Response payment = api.get("/v1/payments/" + id, merchant);
      long captured = payment.json().path("captured_minor").asLong();
      long voided = payment.json().path("voided_minor").asLong();
      // Exactly one of the two terminal outcomes; never a captured-and-voided payment.
      assertThat(captured == 500 && voided == 0 || captured == 0 && voided == 500)
          .as("captured=%d voided=%d", captured, voided)
          .isTrue();
      assertThat(results.stream().filter(t -> t.value().status() == 201).count()).isEqualTo(1);
      LedgerAssertions.assertConsistent(jdbc, id);
    }
  }

  @Test
  void refundRacingTheFirstCaptureNeverRefundsMoreThanWasCaptured() {
    Api api = api();
    String merchant = newMerchant();
    for (int round = 0; round < 10; round++) {
      String id = api.authorizeOk(merchant, 100, "USD");
      Concurrently.run(
          6,
          6,
          i ->
              i % 2 == 0
                  ? api.capture(merchant, newKey(), id, 100, "USD")
                  : api.refund(merchant, newKey(), id, 100, "USD"));
      LedgerAssertions.assertConsistent(jdbc, id);
    }
  }

  @Test
  void theSameIdempotencyKeyFiredConcurrentlyExecutesExactlyOnce() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    String key = "same-key-" + System.nanoTime();

    List<Timed<Response>> results =
        Concurrently.run(30, 30, i -> api.capture(merchant, key, id, 400, "USD"));

    // Every caller either won, replayed the stored answer, or was told to retry (in flight).
    for (Timed<Response> t : results) {
      Response r = t.value();
      assertThat(r.status()).isIn(201, 409);
      if (r.status() == 409) {
        assertThat(r.code()).isEqualTo("REQUEST_IN_PROGRESS");
      }
    }
    long originals =
        results.stream()
            .filter(
                t -> t.value().status() == 201 && t.value().header("Idempotency-Replayed") == null)
            .count();
    assertThat(originals).isEqualTo(1);
    assertThat(captured(api, merchant, id)).isEqualTo(400);

    // After the dust settles every retry replays the same bytes.
    Response original =
        results.stream()
            .map(Timed::value)
            .filter(r -> r.status() == 201 && r.header("Idempotency-Replayed") == null)
            .findFirst()
            .orElseThrow();
    Response replay = api.capture(merchant, key, id, 400, "USD");
    assertThat(replay.body()).isEqualTo(original.body());
    LedgerAssertions.assertConsistent(jdbc, id);
  }
}
