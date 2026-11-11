package com.payflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AmountsTest {

  @Test
  void addFailsLoudlyInsteadOfWrappingAround() {
    assertThat(Amounts.add(1, 2)).isEqualTo(3);
    assertThatThrownBy(() -> Amounts.add(Long.MAX_VALUE, 1))
        .isInstanceOfSatisfying(
            DomainViolation.class,
            v -> assertThat(v.error()).isEqualTo(DomainError.AMOUNT_OVERFLOW));
  }

  @Test
  void feeIsFlooredBasisPoints() {
    assertThat(Amounts.feeFor(10_000, 250)).isEqualTo(250);
    assertThat(Amounts.feeFor(1, 250)).isZero();
    assertThat(Amounts.feeFor(39, 250)).isZero();
    assertThat(Amounts.feeFor(40, 250)).isEqualTo(1);
    assertThat(Amounts.feeFor(0, 250)).isZero();
    assertThat(Amounts.feeFor(12_345, 0)).isZero();
    assertThat(Amounts.feeFor(12_345, 10_000)).isEqualTo(12_345);
  }

  @Test
  void feeMatchesExactArithmeticEvenAtTheLimitOfLong() {
    Random random = new Random(42);
    long[] edge = {Long.MAX_VALUE, Long.MAX_VALUE - 1, 9_999, 10_000, 10_001, 1L << 62};
    for (long amount : edge) {
      for (int bps : new int[] {0, 1, 250, 9_999, 10_000}) {
        assertThat(Amounts.feeFor(amount, bps)).isEqualTo(exactFee(amount, bps));
      }
    }
    for (int i = 0; i < 5_000; i++) {
      long amount = random.nextLong(Long.MAX_VALUE);
      int bps = random.nextInt(10_001);
      assertThat(Amounts.feeFor(amount, bps)).isEqualTo(exactFee(amount, bps));
    }
  }

  /** Independent oracle, test-only: arbitrary-precision integer arithmetic. */
  private static long exactFee(long amount, int bps) {
    return BigInteger.valueOf(amount)
        .multiply(BigInteger.valueOf(bps))
        .divide(BigInteger.valueOf(10_000))
        .longValueExact();
  }

  @Test
  void feeRejectsOutOfRangeInputs() {
    assertThatThrownBy(() -> Amounts.feeFor(-1, 250)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Amounts.feeFor(1, 10_001))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Amounts.feeFor(1, -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void uuid7HasVersionVariantAndTimestamp() {
    Instant at = Instant.parse("2026-03-04T05:06:07.123Z");
    Clock clock = Clock.fixed(at, ZoneOffset.UTC);
    UUID id = Uuid7.generate(clock, new Random(1));
    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
    assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(at.toEpochMilli());
  }

  @Test
  void uuid7IsTimeOrdered() {
    Random random = new Random(7);
    UUID earlier =
        Uuid7.generate(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), random);
    UUID later =
        Uuid7.generate(Clock.fixed(Instant.parse("2026-01-01T00:00:01Z"), ZoneOffset.UTC), random);
    assertThat(earlier.toString().compareTo(later.toString())).isNegative();
  }
}
