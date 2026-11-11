package com.payflow.application;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with "full jitter": the n-th delay is drawn uniformly from [0, min(cap, base
 * * 2^(n-1))]. Spreading retries over the whole window avoids the thundering herd that plain
 * exponential backoff produces after a shared outage or a serialization storm.
 */
public record BackoffPolicy(Duration base, Duration cap) {

  public BackoffPolicy {
    if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
      throw new IllegalArgumentException("require 0 < base <= cap");
    }
  }

  /** Upper bound of the jitter window for a 1-based attempt number; never overflows. */
  public Duration ceilingFor(int attempt) {
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt is 1-based");
    }
    long baseMillis = base.toMillis();
    long capMillis = cap.toMillis();
    int shift = attempt - 1;
    if (shift >= 62 || baseMillis > (Long.MAX_VALUE >> shift)) {
      return cap;
    }
    return Duration.ofMillis(Math.min(capMillis, baseMillis << shift));
  }

  public Duration delayFor(int attempt, RandomGenerator random) {
    long ceiling = ceilingFor(attempt).toMillis();
    return Duration.ofMillis(random.nextLong(ceiling + 1));
  }
}
