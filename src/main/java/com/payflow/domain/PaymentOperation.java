package com.payflow.domain;

import java.time.Instant;
import java.util.UUID;

/** One authorize/capture/refund/void against a payment. */
public record PaymentOperation(
    UUID id,
    UUID paymentId,
    OperationType type,
    long amountMinor,
    long feeMinor,
    String state,
    String pspReference,
    String pspStatus,
    Instant createdAt) {

  public static final String SUCCEEDED = "SUCCEEDED";
  public static final String PSP_PENDING = "PENDING";
  public static final String PSP_FAILED = "FAILED";

  public static PaymentOperation succeeded(
      UUID id,
      UUID paymentId,
      OperationType type,
      long amountMinor,
      long feeMinor,
      String pspReference,
      Instant now) {
    return new PaymentOperation(
        id, paymentId, type, amountMinor, feeMinor, SUCCEEDED, pspReference, PSP_PENDING, now);
  }
}
