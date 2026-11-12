package com.payflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
    assertThat(gate.inFlightTotal()).isZero();
  }

  @Test
  void aWaiterGivesUpAfterTheBoundedWaitWithA503AndLeavesTheHolderUntouched() throws Exception {
    HotKeyGate shortGate = new HotKeyGate(Duration.ofMillis(100), 50);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<String> holder = pool.submit(() -> hold(shortGate, "payment-3", holding, release));
      assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> shortGate.run("payment-3", () -> "never"))
          .isInstanceOfSatisfying(ApiException.class, HotKeyGateTest::assertBusy);

      release.countDown();
      assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held");
      assertThat(shortGate.run("payment-3", () -> "after")).isEqualTo("after");
      assertThat(shortGate.inFlightTotal()).isZero();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void aFullQueueRejectsImmediatelyInsteadOfWaiting() throws Exception {
    // Wait limit is a full minute: if the cap did not reject, this test would hang for that long.
    HotKeyGate capped = new HotKeyGate(Duration.ofSeconds(60), 3);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<String>> inside = new ArrayList<>();
      inside.add(pool.submit(() -> hold(capped, "payment-4", holding, release)));
      assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
      // Two more fill the queue (1 running + 2 waiting = the cap of 3).
      inside.add(pool.submit(() -> capped.run("payment-4", () -> "w1")));
      inside.add(pool.submit(() -> capped.run("payment-4", () -> "w2")));
      waitUntil(() -> capped.inFlightTotal() == 3);

      long started = System.nanoTime();
      assertThatThrownBy(() -> capped.run("payment-4", () -> "overflow"))
          .isInstanceOfSatisfying(ApiException.class, HotKeyGateTest::assertBusy);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertThat(elapsedMillis).isLessThan(2_000);
      assertThat(capped.inFlightTotal()).isEqualTo(3); // the rejected call did not leak a slot

      release.countDown();
      for (Future<String> f : inside) {
        f.get(10, TimeUnit.SECONDS);
      }
      assertThat(capped.inFlightTotal()).isZero();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void theCounterReturnsToZeroAfterABurstOfRejectedTimedOutAndFailedCallsAndExclusionHolds()
      throws Exception {
    // cap 5, wait 30 ms, work 25 ms: with 64 threads on one key some calls are rejected (cap),
    // some time out (queued behind two or more holders), some fail, and some succeed.
    HotKeyGate burst = new HotKeyGate(Duration.ofMillis(30), 5);
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger maxInside = new AtomicInteger();
    AtomicInteger succeeded = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    AtomicInteger busy = new AtomicInteger();
    AtomicInteger calls = new AtomicInteger();
    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800);
    ExecutorService pool = Executors.newFixedThreadPool(64);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < 64; t++) {
        futures.add(
            pool.submit(
                () -> {
                  while (System.nanoTime() < end) {
                    int n = calls.getAndIncrement();
                    try {
                      burst.run(
                          "payment-5",
                          () -> {
                            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                            try {
                              Thread.sleep(25);
                              if (n % 3 == 0) {
                                throw new IllegalStateException("work failed");
                              }
                              succeeded.incrementAndGet();
                              return n;
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            } finally {
                              inside.decrementAndGet();
                            }
                          });
                    } catch (ApiException e) {
                      assertBusy(e);
                      busy.incrementAndGet();
                    } catch (IllegalStateException e) {
                      failed.incrementAndGet();
                    }
                  }
                }));
      }
      for (Future<?> f : futures) {
        f.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(maxInside.get()).as("mutual exclusion while rejecting and timing out").isEqualTo(1);
    assertThat(succeeded.get()).isPositive();
    assertThat(failed.get()).isPositive();
    assertThat(busy.get()).as("rejected by the cap or timed out").isPositive();
    assertThat(succeeded.get() + failed.get() + busy.get()).isEqualTo(calls.get());
    assertThat(burst.inFlightTotal()).as("no leaked slot").isZero();
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
    assertThat(gate.inFlightTotal()).isZero();
  }

  private static String hold(
      HotKeyGate target, String key, CountDownLatch holding, CountDownLatch release) {
    return target.run(
        key,
        () -> {
          holding.countDown();
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return "held";
        });
  }

  private static void assertBusy(ApiException e) {
    assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_BUSY);
    assertThat(e.code().status()).isEqualTo(503);
    assertThat(e.retryAfterSeconds()).isEqualTo(1);
  }

  private static void waitUntil(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached in time");
      }
      Thread.sleep(5);
    }
  }
}
