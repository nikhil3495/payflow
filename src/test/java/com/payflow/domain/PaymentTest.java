package com.payflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private static Payment auth(long amount) {
    return Payment.authorize(UUID.randomUUID(), "m", amount, "USD", "psp", "ref", NOW);
  }

  private static void assertViolation(Runnable action, DomainError expected) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DomainViolation.class, v -> assertThat(v.error()).isEqualTo(expected));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, Long.MIN_VALUE})
  void rejectsNonPositiveAmounts(long amount) {
    assertViolation(() -> auth(amount), DomainError.INVALID_AMOUNT);
    Payment p = auth(100).capture(50, "USD", NOW);
    assertViolation(() -> p.capture(amount, "USD", NOW), DomainError.INVALID_AMOUNT);
    assertViolation(() -> p.refund(amount, "USD", NOW), DomainError.INVALID_AMOUNT);
  }

  @Test
  void multiplePartialCapturesMaySumExactlyToTheAuthorization() {
    Payment p = auth(100).capture(33, "USD", NOW).capture(33, "USD", NOW).capture(34, "USD", NOW);
    assertThat(p.capturedMinor()).isEqualTo(100);
    assertThat(p.state()).isEqualTo(PaymentState.CAPTURED);
  }

  @Test
  void partialCapturesSummingOneMinorUnitOverAreRejected() {
    Payment p = auth(100).capture(33, "USD", NOW).capture(33, "USD", NOW);
    assertViolation(() -> p.capture(35, "USD", NOW), DomainError.AMOUNT_EXCEEDS_CAPTURABLE);
    assertThat(p.capture(34, "USD", NOW).capturedMinor()).isEqualTo(100);
  }

  @Test
  void refundCannotExceedWhatWasCaptured() {
    Payment p = auth(100).capture(40, "USD", NOW);
    assertViolation(() -> p.refund(41, "USD", NOW), DomainError.AMOUNT_EXCEEDS_REFUNDABLE);
    Payment refunded = p.refund(25, "USD", NOW);
    assertViolation(() -> refunded.refund(16, "USD", NOW), DomainError.AMOUNT_EXCEEDS_REFUNDABLE);
    assertThat(refunded.refund(15, "USD", NOW).refundedMinor()).isEqualTo(40);
  }

  @Test
  void refundBeforeCaptureIsIllegal() {
    assertViolation(() -> auth(100).refund(1, "USD", NOW), DomainError.INVALID_STATE_TRANSITION);
  }

  @Test
  void refundOnAVoidedPaymentIsIllegal() {
    Payment voided = auth(100).voidRemainder(NOW);
    assertViolation(() -> voided.refund(1, "USD", NOW), DomainError.INVALID_STATE_TRANSITION);
  }

  @Test
  void captureAfterVoidIsIllegalAndDoubleVoidIsIllegal() {
    Payment voided = auth(100).voidRemainder(NOW);
    assertViolation(() -> voided.capture(1, "USD", NOW), DomainError.INVALID_STATE_TRANSITION);
    assertViolation(() -> voided.voidRemainder(NOW), DomainError.INVALID_STATE_TRANSITION);
  }

  @Test
  void voidAfterPartialCaptureReleasesOnlyTheRemainder() {
    Payment p = auth(100).capture(30, "USD", NOW).voidRemainder(NOW);
    assertThat(p.capturedMinor()).isEqualTo(30);
    assertThat(p.voidedMinor()).isEqualTo(70);
    assertThat(p.state()).isEqualTo(PaymentState.CAPTURED);
    assertViolation(() -> p.capture(1, "USD", NOW), DomainError.INVALID_STATE_TRANSITION);
    assertViolation(() -> p.voidRemainder(NOW), DomainError.INVALID_STATE_TRANSITION);
    assertThat(p.refund(30, "USD", NOW).state()).isEqualTo(PaymentState.REFUNDED);
  }

  @Test
  void voidingTheRemainderAfterAPartialRefundWorksOnce() {
    Payment p = auth(100).capture(40, "USD", NOW).refund(10, "USD", NOW);
    assertThat(p.state()).isEqualTo(PaymentState.PARTIALLY_REFUNDED);
    Payment voided = p.voidRemainder(NOW);
    assertThat(voided.voidedMinor()).isEqualTo(60);
    assertViolation(() -> voided.voidRemainder(NOW), DomainError.NOTHING_TO_VOID);
  }

  @Test
  void captureIsRefusedOnceAnyRefundExists() {
    Payment p = auth(100).capture(40, "USD", NOW).refund(10, "USD", NOW);
    assertViolation(() -> p.capture(1, "USD", NOW), DomainError.INVALID_STATE_TRANSITION);
  }

  @Test
  void currencyMismatchIsRejectedOnEveryOperation() {
    Payment p = auth(100).capture(50, "USD", NOW);
    assertViolation(() -> p.capture(1, "EUR", NOW), DomainError.CURRENCY_MISMATCH);
    assertViolation(() -> p.refund(1, "EUR", NOW), DomainError.CURRENCY_MISMATCH);
  }

  @Test
  void maximumAuthorizationCanBeCapturedInPiecesWithoutOverflow() {
    Payment p = auth(Long.MAX_VALUE);
    Payment almost = p.capture(Long.MAX_VALUE - 1, "USD", NOW);
    assertViolation(() -> almost.capture(2, "USD", NOW), DomainError.AMOUNT_EXCEEDS_CAPTURABLE);
    assertViolation(
        () -> almost.capture(Long.MAX_VALUE, "USD", NOW), DomainError.AMOUNT_EXCEEDS_CAPTURABLE);
    Payment full = almost.capture(1, "USD", NOW);
    assertThat(full.capturedMinor()).isEqualTo(Long.MAX_VALUE);
    assertThat(full.refund(Long.MAX_VALUE, "USD", NOW).state()).isEqualTo(PaymentState.REFUNDED);
  }

  @Test
  void transitionsDoNotMutateTheOriginal() {
    Payment p = auth(100);
    p.capture(10, "USD", NOW);
    assertThat(p.capturedMinor()).isZero();
    assertThat(p.state()).isEqualTo(PaymentState.AUTHORIZED);
  }
}
