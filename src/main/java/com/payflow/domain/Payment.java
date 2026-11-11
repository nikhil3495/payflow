package com.payflow.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable payment aggregate. Every transition returns a new instance; nothing here touches the
 * database, so the rules are testable (and property-testable) in isolation.
 *
 * <p>All amounts are minor units. Remainders are computed by subtraction from the authorization so
 * no sum of two counters can overflow a {@code long}.
 */
public record Payment(
    UUID id,
    String merchantId,
    long amountMinor,
    String currency,
    long authorizedMinor,
    long capturedMinor,
    long refundedMinor,
    long voidedMinor,
    PaymentState state,
    String pspReference,
    String reference,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public static Payment authorize(
      UUID id,
      String merchantId,
      long amountMinor,
      String currency,
      String pspReference,
      String reference,
      Instant now) {
    Amounts.requirePositive(amountMinor);
    return new Payment(
        id,
        merchantId,
        amountMinor,
        currency,
        amountMinor,
        0,
        0,
        0,
        PaymentState.AUTHORIZED,
        pspReference,
        reference,
        0,
        now,
        now);
  }

  /** Authorized money that has been neither captured nor released by a void. */
  public long remainingMinor() {
    return authorizedMinor - capturedMinor - voidedMinor;
  }

  public long refundableMinor() {
    return capturedMinor - refundedMinor;
  }

  public Payment capture(long amount, String requestCurrency, Instant now) {
    Amounts.requirePositive(amount);
    requireCurrency(requestCurrency);
    requireAllowed(OperationType.CAPTURE);
    if (amount > remainingMinor()) {
      throw new DomainViolation(
          DomainError.AMOUNT_EXCEEDS_CAPTURABLE,
          "capture of %d exceeds the capturable amount of %d".formatted(amount, remainingMinor()));
    }
    return withCounters(Amounts.add(capturedMinor, amount), refundedMinor, voidedMinor, now);
  }

  public Payment refund(long amount, String requestCurrency, Instant now) {
    Amounts.requirePositive(amount);
    requireCurrency(requestCurrency);
    requireAllowed(OperationType.REFUND);
    if (amount > refundableMinor()) {
      throw new DomainViolation(
          DomainError.AMOUNT_EXCEEDS_REFUNDABLE,
          "refund of %d exceeds the refundable amount of %d".formatted(amount, refundableMinor()));
    }
    return withCounters(capturedMinor, Amounts.add(refundedMinor, amount), voidedMinor, now);
  }

  /** Releases whatever has not been captured. */
  public Payment voidRemainder(Instant now) {
    requireAllowed(OperationType.VOID);
    long released = remainingMinor();
    if (released == 0) {
      throw new DomainViolation(
          DomainError.NOTHING_TO_VOID, "there is no uncaptured amount to void");
    }
    return withCounters(capturedMinor, refundedMinor, Amounts.add(voidedMinor, released), now);
  }

  public Payment withVersion(long newVersion) {
    return new Payment(
        id,
        merchantId,
        amountMinor,
        currency,
        authorizedMinor,
        capturedMinor,
        refundedMinor,
        voidedMinor,
        state,
        pspReference,
        reference,
        newVersion,
        createdAt,
        updatedAt);
  }

  private void requireCurrency(String requestCurrency) {
    if (!currency.equals(requestCurrency)) {
      throw new DomainViolation(
          DomainError.CURRENCY_MISMATCH,
          "payment currency is %s but the request used %s".formatted(currency, requestCurrency));
    }
  }

  private void requireAllowed(OperationType operation) {
    if (!PaymentStateMachine.isAllowed(state, operation)) {
      throw new DomainViolation(
          DomainError.INVALID_STATE_TRANSITION,
          "%s is not allowed while the payment is %s".formatted(operation, state));
    }
  }

  private Payment withCounters(long captured, long refunded, long voided, Instant now) {
    return new Payment(
        id,
        merchantId,
        amountMinor,
        currency,
        authorizedMinor,
        captured,
        refunded,
        voided,
        PaymentStateMachine.derive(authorizedMinor, captured, refunded, voided),
        pspReference,
        reference,
        version,
        createdAt,
        now);
  }
}
