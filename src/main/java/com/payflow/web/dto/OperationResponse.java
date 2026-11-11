package com.payflow.web.dto;

import com.payflow.application.PaymentService.Outcome;
import java.time.Instant;
import java.util.UUID;

/** A created capture, refund or void together with the payment's resulting state. */
public record OperationResponse(
    UUID id,
    String type,
    long amountMinor,
    String state,
    Instant createdAt,
    PaymentResponse payment) {

  public static OperationResponse from(Outcome outcome) {
    var op = outcome.operation();
    return new OperationResponse(
        op.id(),
        op.type().name(),
        op.amountMinor(),
        op.state(),
        op.createdAt(),
        PaymentResponse.from(outcome.payment()));
  }
}
