package com.payflow.domain;

/**
 * Which operations a payment accepts in each state, and how the state follows from the money
 * counters. Amount rules (over-capture, over-refund) live on {@link Payment}; this class owns only
 * the state-level rules so they can be tested exhaustively.
 */
public final class PaymentStateMachine {

  private PaymentStateMachine() {}

  /**
   * AUTHORIZE is never allowed on an existing payment: it creates one. Once any refund exists no
   * further capture is accepted, but the uncaptured remainder can still be voided.
   */
  public static boolean isAllowed(PaymentState state, OperationType operation) {
    return switch (operation) {
      case AUTHORIZE -> false;
      case CAPTURE -> state == PaymentState.AUTHORIZED || state == PaymentState.PARTIALLY_CAPTURED;
      case REFUND ->
          state == PaymentState.PARTIALLY_CAPTURED
              || state == PaymentState.CAPTURED
              || state == PaymentState.PARTIALLY_REFUNDED;
      case VOID ->
          state == PaymentState.AUTHORIZED
              || state == PaymentState.PARTIALLY_CAPTURED
              || state == PaymentState.PARTIALLY_REFUNDED;
    };
  }

  /** The single source of truth for a payment's state given its counters. */
  public static PaymentState derive(long authorized, long captured, long refunded, long voided) {
    long remainder = authorized - captured - voided;
    if (captured == 0 && voided > 0) {
      return PaymentState.VOIDED;
    }
    if (refunded > 0) {
      return refunded == captured && remainder == 0
          ? PaymentState.REFUNDED
          : PaymentState.PARTIALLY_REFUNDED;
    }
    if (captured == 0) {
      return PaymentState.AUTHORIZED;
    }
    return remainder == 0 ? PaymentState.CAPTURED : PaymentState.PARTIALLY_CAPTURED;
  }
}
