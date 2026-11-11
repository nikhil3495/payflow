package com.payflow.web.dto;

import com.payflow.domain.Payment;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    String merchantId,
    long amountMinor,
    String currency,
    long authorizedMinor,
    long capturedMinor,
    long refundedMinor,
    long voidedMinor,
    String state,
    String reference,
    Instant createdAt,
    Instant updatedAt) {

  public static PaymentResponse from(Payment p) {
    return new PaymentResponse(
        p.id(),
        p.merchantId(),
        p.amountMinor(),
        p.currency(),
        p.authorizedMinor(),
        p.capturedMinor(),
        p.refundedMinor(),
        p.voidedMinor(),
        p.state().name(),
        p.reference(),
        p.createdAt(),
        p.updatedAt());
  }
}
