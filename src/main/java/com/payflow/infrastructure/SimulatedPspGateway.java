package com.payflow.infrastructure;

import com.payflow.application.PspGateway;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for a real provider. References are derived from the idempotency key, so
 * repeating a call (as a retried transaction does) returns the same reference.
 *
 * <p>Payment-method tokens select the outcome: {@code tok_decline} is refused, {@code tok_psp_down}
 * simulates a provider outage; anything else is approved.
 */
@Component
public class SimulatedPspGateway implements PspGateway {

  public static final String DECLINE_TOKEN = "tok_decline";
  public static final String OUTAGE_TOKEN = "tok_psp_down";

  @Override
  public String authorize(
      String idempotencyKey, String paymentMethodToken, long amountMinor, String currency) {
    if (DECLINE_TOKEN.equals(paymentMethodToken)) {
      throw new DeclinedException("The card was declined by the issuer.");
    }
    if (OUTAGE_TOKEN.equals(paymentMethodToken)) {
      throw new UnavailableException("The payment provider is not responding.");
    }
    return reference("auth", idempotencyKey);
  }

  @Override
  public String capture(String idempotencyKey, String authorizationReference, long amountMinor) {
    return reference("cap", idempotencyKey);
  }

  @Override
  public String refund(String idempotencyKey, String authorizationReference, long amountMinor) {
    return reference("ref", idempotencyKey);
  }

  @Override
  public String voidAuthorization(String idempotencyKey, String authorizationReference) {
    return reference("void", idempotencyKey);
  }

  private static String reference(String kind, String idempotencyKey) {
    return "psp_" + kind + "_" + idempotencyKey.replace("-", "");
  }
}
