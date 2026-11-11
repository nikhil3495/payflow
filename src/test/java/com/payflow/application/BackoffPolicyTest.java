package com.payflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

  private static final BackoffPolicy WEBHOOKS =
      new BackoffPolicy(Duration.ofSeconds(30), Duration.ofHours(1));

  /** A "random" source pinned to its minimum or maximum so jitter bounds are exact. */
  private static RandomGenerator pinned(boolean max) {
    return new RandomGenerator() {
      @Override
      public long nextLong() {
        return max ? Long.MAX_VALUE : 0;
      }

      @Override
      public long nextLong(long bound) {
        return max ? bound - 1 : 0;
      }
    };
  }

  @Test
  void theJitterCeilingDoublesPerAttemptAndIsCapped() {
    assertThat(WEBHOOKS.ceilingFor(1)).isEqualTo(Duration.ofSeconds(30));
    assertThat(WEBHOOKS.ceilingFor(2)).isEqualTo(Duration.ofSeconds(60));
    assertThat(WEBHOOKS.ceilingFor(3)).isEqualTo(Duration.ofSeconds(120));
    assertThat(WEBHOOKS.ceilingFor(4)).isEqualTo(Duration.ofSeconds(240));
    assertThat(WEBHOOKS.ceilingFor(7)).isEqualTo(Duration.ofMinutes(32));
    assertThat(WEBHOOKS.ceilingFor(8)).isEqualTo(Duration.ofHours(1));
    assertThat(WEBHOOKS.ceilingFor(9)).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void theCeilingNeverOverflowsForAbsurdAttemptNumbers() {
    assertThat(WEBHOOKS.ceilingFor(40)).isEqualTo(Duration.ofHours(1));
    assertThat(WEBHOOKS.ceilingFor(62)).isEqualTo(Duration.ofHours(1));
    assertThat(WEBHOOKS.ceilingFor(63)).isEqualTo(Duration.ofHours(1));
    assertThat(WEBHOOKS.ceilingFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void fullJitterSpansTheWholeWindowFromZeroToTheCeiling() {
    for (int attempt = 1; attempt <= 10; attempt++) {
      assertThat(WEBHOOKS.delayFor(attempt, pinned(false))).isEqualTo(Duration.ZERO);
      assertThat(WEBHOOKS.delayFor(attempt, pinned(true))).isEqualTo(WEBHOOKS.ceilingFor(attempt));
    }
  }

  @Test
  void sampledDelaysStayWithinTheWindowAndActuallyVary() {
    java.util.Random random = new java.util.Random(99);
    java.util.Set<Long> distinct = new java.util.HashSet<>();
    for (int i = 0; i < 500; i++) {
      Duration delay = WEBHOOKS.delayFor(3, random);
      assertThat(delay).isBetween(Duration.ZERO, Duration.ofSeconds(120));
      distinct.add(delay.toMillis());
    }
    assertThat(distinct.size()).isGreaterThan(400);
  }

  @Test
  void theScheduleForAFullRetryBudgetIsBoundedByTheSumOfCeilings() {
    long worstCaseSeconds = 0;
    for (int attempt = 1; attempt <= 7; attempt++) {
      worstCaseSeconds += WEBHOOKS.ceilingFor(attempt).toSeconds();
    }
    assertThat(worstCaseSeconds).isEqualTo(30 + 60 + 120 + 240 + 480 + 960 + 1920);
  }

  @Test
  void invalidParametersAreRejected() {
    assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WEBHOOKS.ceilingFor(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
