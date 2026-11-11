package com.payflow.domain;

/**
 * Integer minor-unit arithmetic. Money is never a double or a BigDecimal; every operation either
 * returns an exact result or fails loudly.
 */
public final class Amounts {

  private static final long BPS_DENOMINATOR = 10_000L;

  private Amounts() {}

  public static long add(long a, long b) {
    try {
      return Math.addExact(a, b);
    } catch (ArithmeticException e) {
      throw new DomainViolation(DomainError.AMOUNT_OVERFLOW, "amount arithmetic overflowed");
    }
  }

  public static long requirePositive(long amountMinor) {
    if (amountMinor <= 0) {
      throw new DomainViolation(DomainError.INVALID_AMOUNT, "amount_minor must be greater than 0");
    }
    return amountMinor;
  }

  /**
   * floor(amount * bps / 10_000) computed without an intermediate product that could overflow:
   * amount = q*10_000 + r, so amount*bps/10_000 = q*bps + floor(r*bps/10_000).
   */
  public static long feeFor(long amountMinor, int feeBps) {
    if (amountMinor < 0 || feeBps < 0 || feeBps > BPS_DENOMINATOR) {
      throw new IllegalArgumentException("amount must be >= 0 and bps within [0, 10000]");
    }
    long quotient = amountMinor / BPS_DENOMINATOR;
    long remainder = amountMinor % BPS_DENOMINATOR;
    return quotient * feeBps + (remainder * feeBps) / BPS_DENOMINATOR;
  }
}
