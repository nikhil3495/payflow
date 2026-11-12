package com.payflow.application;

import com.payflow.domain.DomainError;
import java.util.Locale;

/** Stable, machine-readable error codes. Clients may branch on these; they never change. */
public enum ErrorCode {
  MISSING_MERCHANT_ID(400, "Missing merchant id"),
  MISSING_IDEMPOTENCY_KEY(400, "Missing idempotency key"),
  INVALID_IDEMPOTENCY_KEY(400, "Invalid idempotency key"),
  VALIDATION_ERROR(400, "Request validation failed"),
  MALFORMED_REQUEST(400, "Malformed request"),
  INVALID_PAYMENT_ID(400, "Invalid payment id"),
  INVALID_AMOUNT(400, "Invalid amount"),
  UNAUTHORIZED(401, "Unauthorized"),
  INVALID_WEBHOOK_SIGNATURE(401, "Invalid webhook signature"),
  PAYMENT_DECLINED(402, "Payment declined"),
  PAYMENT_NOT_FOUND(404, "Payment not found"),
  NOT_FOUND(404, "Resource not found"),
  METHOD_NOT_ALLOWED(405, "Method not allowed"),
  REQUEST_IN_PROGRESS(409, "Request already in progress"),
  INVALID_STATE_TRANSITION(409, "Operation not allowed in the current payment state"),
  AMOUNT_EXCEEDS_CAPTURABLE(409, "Amount exceeds the capturable amount"),
  AMOUNT_EXCEEDS_REFUNDABLE(409, "Amount exceeds the refundable amount"),
  NOTHING_TO_VOID(409, "Nothing to void"),
  EVENT_NOT_REPLAYABLE(409, "Inbound event cannot be replayed"),
  PAYLOAD_TOO_LARGE(413, "Request body too large"),
  UNSUPPORTED_MEDIA_TYPE(415, "Unsupported media type"),
  IDEMPOTENCY_KEY_REUSED(422, "Idempotency key reused with a different request"),
  CURRENCY_MISMATCH(422, "Currency does not match the payment"),
  UNSUPPORTED_CURRENCY(422, "Unsupported currency"),
  AMOUNT_OVERFLOW(422, "Amount overflow"),
  INTERNAL_ERROR(500, "Internal error"),
  PSP_UNAVAILABLE(502, "Payment provider unavailable"),
  SERVICE_BUSY(503, "Service busy, retry later");

  private final int status;
  private final String title;

  ErrorCode(int status, String title) {
    this.status = status;
    this.title = title;
  }

  public int status() {
    return status;
  }

  public String title() {
    return title;
  }

  /**
   * The URN identifying the problem type, e.g. {@code
   * urn:payflow:problem:amount-exceeds-capturable}.
   */
  public String typeUri() {
    return "urn:payflow:problem:" + name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  public static ErrorCode from(DomainError error) {
    return valueOf(error.name());
  }
}
