package com.payflow.domain;

/** Lifecycle state of a payment. It is always derived from the four money counters. */
public enum PaymentState {
  AUTHORIZED,
  PARTIALLY_CAPTURED,
  CAPTURED,
  PARTIALLY_REFUNDED,
  REFUNDED,
  VOIDED
}
