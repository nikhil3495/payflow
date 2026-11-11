package com.payflow.domain;

/** Business-rule failures. The application layer maps each one to a stable API error code. */
public enum DomainError {
  INVALID_AMOUNT,
  CURRENCY_MISMATCH,
  INVALID_STATE_TRANSITION,
  AMOUNT_EXCEEDS_CAPTURABLE,
  AMOUNT_EXCEEDS_REFUNDABLE,
  NOTHING_TO_VOID,
  AMOUNT_OVERFLOW
}
