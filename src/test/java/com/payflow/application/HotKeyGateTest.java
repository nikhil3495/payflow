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
  void aWaiterGivesUpAfterTheBoundedWaitWithA503AndLeavesTheHolderUntouched() throws Exception {
    HotKeyGate shortGate = new HotKeyGate(java.time.Duration.ofMillis(100));
    java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<String> holder =
          pool.submit(
              () ->
                  shortGate.run(
                      "payment-3",
                      () -> {
                        holding.countDown();
                        try {
                          release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return "held";
                      }));
      assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

      long started = System.nanoTime();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> shortGate.run("payment-3", () -> "never"))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_BUSY);
                assertThat(e.code().status()).isEqualTo(503);
                assertThat(e.retryAfterSeconds()).isEqualTo(1);
              });
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000);

      release.countDown();
      assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held");
      assertThat(shortGate.run("payment-3", () -> "after")).isEqualTo("after");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
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
