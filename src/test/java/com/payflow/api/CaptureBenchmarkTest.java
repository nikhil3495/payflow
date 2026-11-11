package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.Concurrently;
import com.payflow.support.Concurrently.Timed;
import com.payflow.support.IntegrationTest;
import com.payflow.support.LedgerAssertions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 500 concurrent full-amount captures against ONE authorization, from a real thread pool through
 * the real HTTP stack into a real PostgreSQL. Exactly one may win. The measured distribution is
 * printed and appended to {@code target/benchmark-results.txt}; the README quotes those numbers.
 */
class CaptureBenchmarkTest extends IntegrationTest {

  private static final int REQUESTS = 500;
  private static final int THREADS = 100;
  private static final long AMOUNT = 10_000;

  @Test
  void fiveHundredConcurrentCapturesYieldExactlyOneSuccess() throws IOException {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, AMOUNT, "USD");

    long started = System.nanoTime();
    List<Timed<Response>> results =
        Concurrently.run(
            REQUESTS, THREADS, i -> api.capture(merchant, newKey(), id, AMOUNT, "USD"));
    long wallMillis = (System.nanoTime() - started) / 1_000_000;

    Map<Integer, Long> distribution =
        results.stream()
            .collect(
                Collectors.groupingBy(
                    t -> t.value().status(), TreeMap::new, Collectors.counting()));
    long[] latencies = results.stream().mapToLong(Timed::nanos).sorted().toArray();
    String summary =
        "captures=%d threads=%d statuses=%s wall_ms=%d p50_ms=%.1f p95_ms=%.1f max_ms=%.1f"
            .formatted(
                REQUESTS,
                THREADS,
                distribution,
                wallMillis,
                latencies[latencies.length / 2] / 1e6,
                latencies[(int) (latencies.length * 0.95)] / 1e6,
                latencies[latencies.length - 1] / 1e6);
    System.out.println("BENCHMARK " + summary);
    Files.writeString(
        Path.of("target", "benchmark-results.txt"),
        summary + System.lineSeparator(),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND);

    // Exactly one winner; everyone else a clean conflict - never a 5xx, never a second capture.
    assertThat(distribution.get(201)).isEqualTo(1L);
    assertThat(distribution.get(409)).isEqualTo((long) REQUESTS - 1);
    assertThat(distribution).hasSize(2);
    results.stream()
        .filter(t -> t.value().status() == 409)
        .forEach(t -> assertThat(t.value().code()).isEqualTo("INVALID_STATE_TRANSITION"));

    Response payment = api.get("/v1/payments/" + id, merchant);
    assertThat(payment.json().path("captured_minor").asLong()).isEqualTo(AMOUNT);
    assertThat(payment.json().path("state").asText()).isEqualTo("CAPTURED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment_operations WHERE payment_id = ?::uuid AND type = 'CAPTURE'",
                Long.class,
                id))
        .isEqualTo(1);
    LedgerAssertions.assertConsistent(jdbc, id);
  }
}
