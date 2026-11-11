package com.payflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every (state x operation) pair, legal and illegal, asserted explicitly. */
class PaymentStateMachineTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** The complete legality matrix; anything not listed must be rejected. */
  private static final Map<PaymentState, Set<OperationType>> ALLOWED =
      new EnumMap<>(PaymentState.class);

  static {
    ALLOWED.put(PaymentState.AUTHORIZED, EnumSet.of(OperationType.CAPTURE, OperationType.VOID));
    ALLOWED.put(
        PaymentState.PARTIALLY_CAPTURED,
        EnumSet.of(OperationType.CAPTURE, OperationType.REFUND, OperationType.VOID));
    ALLOWED.put(PaymentState.CAPTURED, EnumSet.of(OperationType.REFUND));
    ALLOWED.put(
        PaymentState.PARTIALLY_REFUNDED, EnumSet.of(OperationType.REFUND, OperationType.VOID));
    ALLOWED.put(PaymentState.REFUNDED, EnumSet.noneOf(OperationType.class));
    ALLOWED.put(PaymentState.VOIDED, EnumSet.noneOf(OperationType.class));
  }

  static Stream<Arguments> everyPair() {
    return Stream.of(PaymentState.values())
        .flatMap(state -> Stream.of(OperationType.values()).map(op -> Arguments.of(state, op)));
  }

  /** A payment sitting in {@code state}, built through the real transitions where possible. */
  static Payment payment(PaymentState state) {
    Payment p = Payment.authorize(UUID.randomUUID(), "m", 100, "USD", "psp", null, NOW);
    return switch (state) {
      case AUTHORIZED -> p;
      case PARTIALLY_CAPTURED -> p.capture(40, "USD", NOW);
      case CAPTURED -> p.capture(100, "USD", NOW);
      case PARTIALLY_REFUNDED -> p.capture(100, "USD", NOW).refund(10, "USD", NOW);
      case REFUNDED -> p.capture(100, "USD", NOW).refund(100, "USD", NOW);
      case VOIDED -> p.voidRemainder(NOW);
    };
  }

  @Test
  void fixtureBuildsEachStateFaithfully() {
    for (PaymentState state : PaymentState.values()) {
      assertThat(payment(state).state()).isEqualTo(state);
    }
  }

  @ParameterizedTest(name = "{0} x {1}")
  @MethodSource("everyPair")
  void isAllowedMatchesTheMatrix(PaymentState state, OperationType op) {
    assertThat(PaymentStateMachine.isAllowed(state, op)).isEqualTo(ALLOWED.get(state).contains(op));
  }

  @ParameterizedTest(name = "{0} x {1}")
  @MethodSource("everyPair")
  void paymentEnforcesTheMatrix(PaymentState state, OperationType op) {
    Payment p = payment(state);
    boolean legal = ALLOWED.get(state).contains(op);
    switch (op) {
      case AUTHORIZE ->
          // Authorize creates a payment; there is no instance method, so it is never legal here.
          assertThat(legal).isFalse();
      case CAPTURE -> {
        if (legal) {
          assertThat(p.capture(1, "USD", NOW).capturedMinor()).isEqualTo(p.capturedMinor() + 1);
        } else {
          assertThatThrownBy(() -> p.capture(1, "USD", NOW))
              .isInstanceOfSatisfying(
                  DomainViolation.class,
                  v -> assertThat(v.error()).isEqualTo(DomainError.INVALID_STATE_TRANSITION));
        }
      }
      case REFUND -> {
        if (legal) {
          // A fully refunded-out PARTIALLY_REFUNDED payment legitimately has nothing left to
          // refund.
          if (p.refundableMinor() > 0) {
            assertThat(p.refund(1, "USD", NOW).refundedMinor()).isEqualTo(p.refundedMinor() + 1);
          } else {
            assertThatThrownBy(() -> p.refund(1, "USD", NOW))
                .isInstanceOfSatisfying(
                    DomainViolation.class,
                    v -> assertThat(v.error()).isEqualTo(DomainError.AMOUNT_EXCEEDS_REFUNDABLE));
          }
        } else {
          assertThatThrownBy(() -> p.refund(1, "USD", NOW))
              .isInstanceOfSatisfying(
                  DomainViolation.class,
                  v -> assertThat(v.error()).isEqualTo(DomainError.INVALID_STATE_TRANSITION));
        }
      }
      case VOID -> {
        if (legal && p.remainingMinor() > 0) {
          Payment voided = p.voidRemainder(NOW);
          assertThat(voided.remainingMinor()).isZero();
          assertThat(voided.voidedMinor()).isEqualTo(p.voidedMinor() + p.remainingMinor());
        } else if (legal) {
          assertThatThrownBy(() -> p.voidRemainder(NOW))
              .isInstanceOfSatisfying(
                  DomainViolation.class,
                  v -> assertThat(v.error()).isEqualTo(DomainError.NOTHING_TO_VOID));
        } else {
          assertThatThrownBy(() -> p.voidRemainder(NOW))
              .isInstanceOfSatisfying(
                  DomainViolation.class,
                  v -> assertThat(v.error()).isEqualTo(DomainError.INVALID_STATE_TRANSITION));
        }
      }
    }
  }

  @Test
  void derivedStateFollowsTheCounters() {
    assertThat(PaymentStateMachine.derive(100, 0, 0, 0)).isEqualTo(PaymentState.AUTHORIZED);
    assertThat(PaymentStateMachine.derive(100, 40, 0, 0))
        .isEqualTo(PaymentState.PARTIALLY_CAPTURED);
    assertThat(PaymentStateMachine.derive(100, 100, 0, 0)).isEqualTo(PaymentState.CAPTURED);
    assertThat(PaymentStateMachine.derive(100, 40, 0, 60)).isEqualTo(PaymentState.CAPTURED);
    assertThat(PaymentStateMachine.derive(100, 0, 0, 100)).isEqualTo(PaymentState.VOIDED);
    assertThat(PaymentStateMachine.derive(100, 100, 30, 0))
        .isEqualTo(PaymentState.PARTIALLY_REFUNDED);
    assertThat(PaymentStateMachine.derive(100, 100, 100, 0)).isEqualTo(PaymentState.REFUNDED);
    // Everything captured has been refunded but part of the authorization is still held.
    assertThat(PaymentStateMachine.derive(100, 40, 40, 0))
        .isEqualTo(PaymentState.PARTIALLY_REFUNDED);
    assertThat(PaymentStateMachine.derive(100, 40, 40, 60)).isEqualTo(PaymentState.REFUNDED);
  }
}
