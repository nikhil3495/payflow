package com.payflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HotKeyGateTest {

  private final HotKeyGate gate = new HotKeyGate();

  @Test
  void neverRunsTwoCallsForTheSameKeyAtOnce() throws Exception {
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger maxInside = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      var futures =
          IntStream.range(0, 200)
              .mapToObj(
                  i ->
                      pool.submit(
                          () ->
                              gate.run(
                                  "payment-1",
                                  () -> {
                                    int now = inside.incrementAndGet();
                                    maxInside.accumulateAndGet(now, Math::max);
                                    Thread.onSpinWait();
                                    inside.decrementAndGet();
                                    return now;
                                  })))
              .toList();
      for (Future<Integer> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(maxInside.get()).isEqualTo(1);
  }

  @Test
  void aNullKeyRunsWithoutQueueingAndReturnsTheResult() {
    assertThat(gate.run(null, () -> "done")).isEqualTo("done");
  }

  @Test
  void releasesTheLockWhenTheWorkThrows() {
    try {
      gate.run(
          "payment-2",
          () -> {
            throw new IllegalStateException("boom");
          });
    } catch (IllegalStateException expected) {
      // expected
    }
    assertThat(gate.run("payment-2", () -> "next")).isEqualTo("next");
  }
}
