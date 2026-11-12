package com.payflow.application;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Lets only one request per payment (per stripe) enter the database at a time <em>within this
 * process</em>.
 *
 * <p>Why: under SERIALIZABLE every contender that does {@code SELECT ... FOR UPDATE} on the same
 * row both reads and locks it, so N concurrent contenders create read/write dependency cycles among
 * themselves and PostgreSQL aborts most of them with 40001. Retrying works, but with hundreds of
 * waiters on one payment a request can lose the lottery more often than the retry budget allows.
 * Queueing the contenders before they open a transaction removes that self-inflicted herd; the
 * database lock, the SERIALIZABLE isolation and the bounded retry remain the correctness mechanism
 * (and the only one across several application instances). This gate is purely a throughput and
 * fairness optimisation: dropping it never affects correctness.
 *
 * <p>Striped, so memory is fixed; two payments on the same stripe merely queue behind each other
 * for a few milliseconds. Locks are fair, so waiters are served in arrival order.
 *
 * <p>Bounded queue: at most {@code maxInFlight} requests (the one running plus those waiting) may
 * be inside a stripe at once; the next one is rejected immediately with 503 + Retry-After instead
 * of parking a request thread for up to the wait limit. The counter is incremented before anything
 * can fail and decremented in a single {@code finally}, so it cannot leak on any exit path.
 */
@Component
public final class HotKeyGate {

  private static final int STRIPES = 1024;
  private static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(20);
  static final int DEFAULT_MAX_IN_FLIGHT = 50;

  private final ReentrantLock[] locks = new ReentrantLock[STRIPES];
  private final AtomicInteger[] inFlight = new AtomicInteger[STRIPES];
  private final Duration maxWait;
  private final int maxInFlight;

  public HotKeyGate() {
    this(DEFAULT_MAX_WAIT, DEFAULT_MAX_IN_FLIGHT);
  }

  /** Package-private so tests can use a short wait and a small cap. */
  HotKeyGate(Duration maxWait, int maxInFlight) {
    if (maxInFlight < 1) {
      throw new IllegalArgumentException("maxInFlight must be >= 1");
    }
    this.maxWait = maxWait;
    this.maxInFlight = maxInFlight;
    for (int i = 0; i < STRIPES; i++) {
      locks[i] = new ReentrantLock(true);
      inFlight[i] = new AtomicInteger();
    }
  }

  /**
   * Requests currently inside the gate (running or waiting), all stripes; for tests and metrics.
   */
  int inFlightTotal() {
    int total = 0;
    for (AtomicInteger counter : inFlight) {
      total += counter.get();
    }
    return total;
  }

  /** Runs {@code work} exclusively for {@code key}; a null key runs it without queueing. */
  public <T> T run(String key, Supplier<T> work) {
    if (key == null) {
      return work.get();
    }
    int stripe = Math.floorMod(key.hashCode(), STRIPES);
    AtomicInteger counter = inFlight[stripe];
    if (counter.incrementAndGet() > maxInFlight) {
      counter.decrementAndGet();
      throw busy();
    }
    try {
      ReentrantLock lock = locks[stripe];
      try {
        if (!lock.tryLock(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
          throw busy();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw busy();
      }
      try {
        return work.get();
      } finally {
        lock.unlock();
      }
    } finally {
      counter.decrementAndGet();
    }
  }

  private static ApiException busy() {
    return new ApiException(
        ErrorCode.SERVICE_BUSY,
        "The request could not be completed due to contention; retry it.",
        List.of(),
        1);
  }
}
