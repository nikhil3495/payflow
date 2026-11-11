package com.payflow.infrastructure;

import com.payflow.application.PspGateway;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for a real provider. References are derived from the operation id, so
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
      String operationId, String paymentMethodToken, long amountMinor, String currency) {
    if (DECLINE_TOKEN.equals(paymentMethodToken)) {
      throw new DeclinedException("The card was declined by the issuer.");
    }
    if (OUTAGE_TOKEN.equals(paymentMethodToken)) {
      throw new UnavailableException("The payment provider is not responding.");
    }
    return reference("auth", operationId);
  }

  @Override
  public String capture(String operationId, String authorizationReference, long amountMinor) {
    return reference("cap", operationId);
  }

  @Override
  public String refund(String operationId, String authorizationReference, long amountMinor) {
    return reference("ref", operationId);
  }

  @Override
  public String voidAuthorization(String operationId, String authorizationReference) {
    return reference("void", operationId);
  }

  private static String reference(String kind, String operationId) {
    return "psp_" + kind + "_" + operationId.replace("-", "");
  }
}
