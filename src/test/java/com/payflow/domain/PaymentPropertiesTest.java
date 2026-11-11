package com.payflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.domain.LedgerPostings.EntryDraft;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/** Properties of the pure domain model over random operation sequences. */
class PaymentPropertiesTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  record Step(OperationType type, long amount) {}

  @Provide
  Arbitrary<List<Step>> steps() {
    Arbitrary<OperationType> types =
        Arbitraries.of(OperationType.CAPTURE, OperationType.REFUND, OperationType.VOID);
    // Mostly small amounts so sequences get deep, with the occasional huge or invalid one.
    Arbitrary<Long> amounts =
        Arbitraries.frequencyOf(
            net.jqwik.api.Tuple.of(8, Arbitraries.longs().between(1, 120)),
            net.jqwik.api.Tuple.of(1, Arbitraries.longs().between(-5, 0)),
            net.jqwik.api.Tuple.of(
                1, Arbitraries.longs().between(Long.MAX_VALUE - 5, Long.MAX_VALUE)));
    return Combinators.combine(types, amounts).as(Step::new).list().ofMaxSize(40);
  }

  /** Applies one step if the model accepts it; returns the new payment or null if rejected. */
  private static Payment apply(Payment payment, Step step) {
    try {
      return switch (step.type()) {
        case CAPTURE -> payment.capture(step.amount(), "USD", NOW);
        case REFUND -> payment.refund(step.amount(), "USD", NOW);
        case VOID -> payment.voidRemainder(NOW);
        case AUTHORIZE -> throw new AssertionError("authorize is not a step");
      };
    } catch (DomainViolation rejected) {
      return null;
    }
  }

  @Property(tries = 3000)
  void countersStayWithinBoundsAndLedgerStaysBalanced(
      @ForAll @LongRange(min = 1, max = 500) long authorized, @ForAll("steps") List<Step> steps) {
    Payment payment =
        Payment.authorize(UUID.randomUUID(), "m", authorized, "USD", "psp", null, NOW);
    List<EntryDraft> ledger = new ArrayList<>();
    long previousCaptured = 0;
    long previousRefunded = 0;
    long previousVoided = 0;

    for (Step step : steps) {
      Payment next = apply(payment, step);
      if (next == null) {
        // A rejected step must not have changed anything.
        assertThat(payment.capturedMinor()).isGreaterThanOrEqualTo(previousCaptured);
        continue;
      }
      if (step.type() == OperationType.CAPTURE) {
        ledger.addAll(LedgerPostings.forCapture(step.amount(), Amounts.feeFor(step.amount(), 250)));
      } else if (step.type() == OperationType.REFUND) {
        ledger.addAll(LedgerPostings.forRefund(step.amount()));
      }
      payment = next;

      assertThat(payment.capturedMinor() + payment.voidedMinor())
          .as("captured + voided <= authorized")
          .isLessThanOrEqualTo(payment.authorizedMinor());
      assertThat(payment.refundedMinor())
          .as("refunded <= captured")
          .isLessThanOrEqualTo(payment.capturedMinor());
      assertThat(payment.capturedMinor()).isBetween(previousCaptured, payment.authorizedMinor());
      assertThat(payment.refundedMinor()).isGreaterThanOrEqualTo(previousRefunded);
      assertThat(payment.voidedMinor()).isGreaterThanOrEqualTo(previousVoided);
      assertThat(payment.state())
          .isEqualTo(
              PaymentStateMachine.derive(
                  payment.authorizedMinor(),
                  payment.capturedMinor(),
                  payment.refundedMinor(),
                  payment.voidedMinor()));
      assertThat(LedgerPostings.isBalanced(ledger)).as("ledger balanced").isTrue();
      previousCaptured = payment.capturedMinor();
      previousRefunded = payment.refundedMinor();
      previousVoided = payment.voidedMinor();
    }

    // The ledger's net position always agrees with the counters.
    Map<LedgerAccountType, Long> position = LedgerPostings.netDebitByAccount(ledger);
    assertThat(position.getOrDefault(LedgerAccountType.PSP_CLEARING, 0L))
        .isEqualTo(payment.capturedMinor());
    assertThat(position.getOrDefault(LedgerAccountType.MERCHANT_RECEIVABLE, 0L))
        .isEqualTo(payment.refundedMinor());
    assertThat(position.getOrDefault(LedgerAccountType.REFUND_LIABILITY, 0L))
        .isEqualTo(-payment.refundedMinor());
    assertThat(
            position.getOrDefault(LedgerAccountType.MERCHANT_PAYABLE, 0L)
                + position.getOrDefault(LedgerAccountType.FEE_REVENUE, 0L))
        .isEqualTo(-payment.capturedMinor());
  }

  @Property(tries = 1000)
  void aTerminalPaymentRejectsEverything(@ForAll @LongRange(min = 1, max = 300) long authorized) {
    Payment voided =
        Payment.authorize(UUID.randomUUID(), "m", authorized, "USD", "psp", null, NOW)
            .voidRemainder(NOW);
    assertThat(voided.state()).isEqualTo(PaymentState.VOIDED);
    for (OperationType type :
        List.of(OperationType.CAPTURE, OperationType.REFUND, OperationType.VOID)) {
      assertThat(apply(voided, new Step(type, 1))).isNull();
    }
  }

  @Property(tries = 2000)
  void captureSplitIntoRandomPiecesNeverExceedsTheAuthorization(
      @ForAll @LongRange(min = 1, max = 1000) long authorized,
      @ForAll @Size(min = 1, max = 30) List<@LongRange(min = 1, max = 400) Long> pieces) {
    Payment payment =
        Payment.authorize(UUID.randomUUID(), "m", authorized, "USD", "psp", null, NOW);
    long accepted = 0;
    for (long piece : pieces) {
      Payment next = apply(payment, new Step(OperationType.CAPTURE, piece));
      if (next != null) {
        payment = next;
        accepted += piece;
      } else {
        assertThat(piece).isGreaterThan(authorized - accepted);
      }
    }
    assertThat(accepted).isEqualTo(payment.capturedMinor()).isLessThanOrEqualTo(authorized);
  }

  @Property(tries = 5000)
  void feeIsBoundedByTheAmount(
      @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long amount,
      @ForAll @IntRange(min = 0, max = 10_000) int bps) {
    long fee = Amounts.feeFor(amount, bps);
    assertThat(fee).isBetween(0L, amount);
    if (bps == 10_000) {
      assertThat(fee).isEqualTo(amount);
    }
    if (bps == 0) {
      assertThat(fee).isZero();
    }
  }

  @Provide
  Arbitrary<long[]> validCounters() {
    return Arbitraries.longs()
        .between(1, 50)
        .flatMap(
            authorized ->
                Arbitraries.longs()
                    .between(0, authorized)
                    .flatMap(
                        captured ->
                            Combinators.combine(
                                    Arbitraries.longs().between(0, authorized - captured),
                                    Arbitraries.longs().between(0, captured))
                                .as(
                                    (voided, refunded) ->
                                        new long[] {authorized, captured, refunded, voided})));
  }

  @Property(tries = 3000)
  void everyValidCounterCombinationMapsToTheRightState(@ForAll("validCounters") long[] c) {
    long authorized = c[0];
    long captured = c[1];
    long refunded = c[2];
    long voided = c[3];
    PaymentState state = PaymentStateMachine.derive(authorized, captured, refunded, voided);
    boolean fullySettled = authorized - captured - voided == 0;
    if (captured == 0 && voided > 0) {
      assertThat(state).isEqualTo(PaymentState.VOIDED);
    } else if (refunded > 0) {
      assertThat(state)
          .isEqualTo(
              refunded == captured && fullySettled
                  ? PaymentState.REFUNDED
                  : PaymentState.PARTIALLY_REFUNDED);
    } else if (captured == 0) {
      assertThat(state).isEqualTo(PaymentState.AUTHORIZED);
    } else {
      assertThat(state)
          .isEqualTo(fullySettled ? PaymentState.CAPTURED : PaymentState.PARTIALLY_CAPTURED);
    }
  }
}
