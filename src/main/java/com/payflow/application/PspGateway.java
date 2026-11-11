package com.payflow.application;

/**
 * Port to the payment service provider. Every call carries an {@code idempotencyKey} that is stable
 * across retries of the surrounding transaction (which is re-run after a serialization failure).
 * Implementations must forward it to the PSP so a repeated call has no second effect.
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
      String idempotencyKey, String paymentMethodToken, long amountMinor, String currency);

  String capture(String idempotencyKey, String authorizationReference, long amountMinor);

  String refund(String idempotencyKey, String authorizationReference, long amountMinor);

  String voidAuthorization(String idempotencyKey, String authorizationReference);
}
