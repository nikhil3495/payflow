package com.payflow.application;

/**
 * Port to the payment service provider. Implementations must be idempotent per {@code operationId}:
 * the surrounding transaction can be retried after a serialization failure, so the same logical
 * call may be issued more than once.
 */
public interface PspGateway {

  /** The card was refused; deterministic, so surfaced to the client as a 402. */
  final class DeclinedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DeclinedException(String message) {
      super(message);
    }
  }

  /** The provider could not be reached or errored; transient, surfaced as a retryable 502. */
  final class UnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnavailableException(String message) {
      super(message);
    }
  }

  /** Places a hold and returns the provider's reference. */
  String authorize(
      String operationId, String paymentMethodToken, long amountMinor, String currency);

  String capture(String operationId, String authorizationReference, long amountMinor);

  String refund(String operationId, String authorizationReference, long amountMinor);

  String voidAuthorization(String operationId, String authorizationReference);
}
