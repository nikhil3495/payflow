package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class PaymentFlowTest extends IntegrationTest {

  @Test
  void happyPathAuthorizePartialCapturePartialRefundVoidRemainder() {
    Api api = api();
    String merchant = newMerchant();

    Response authorized = api.authorize(merchant, newKey(), 10_000, "USD");
    assertThat(authorized.status()).isEqualTo(201);
    assertThat(authorized.header("Content-Type")).startsWith("application/json");
    String id = authorized.json().path("id").asText();
    assertThat(authorized.json().path("state").asText()).isEqualTo("AUTHORIZED");
    assertThat(authorized.json().path("authorized_minor").asLong()).isEqualTo(10_000);
    assertThat(java.util.UUID.fromString(id).version()).isEqualTo(7);

    Response captured = api.capture(merchant, newKey(), id, 4_000, "USD");
    assertThat(captured.status()).isEqualTo(201);
    assertThat(captured.json().path("type").asText()).isEqualTo("CAPTURE");
    JsonNode afterCapture = captured.json().path("payment");
    assertThat(afterCapture.path("captured_minor").asLong()).isEqualTo(4_000);
    assertThat(afterCapture.path("state").asText()).isEqualTo("PARTIALLY_CAPTURED");

    Response refunded = api.refund(merchant, newKey(), id, 1_000, "USD");
    assertThat(refunded.status()).isEqualTo(201);
    assertThat(refunded.json().path("payment").path("refunded_minor").asLong()).isEqualTo(1_000);
    assertThat(refunded.json().path("payment").path("state").asText())
        .isEqualTo("PARTIALLY_REFUNDED");

    Response voided = api.voidPayment(merchant, newKey(), id);
    assertThat(voided.status()).isEqualTo(201);
    JsonNode finalPayment = voided.json().path("payment");
    assertThat(voided.json().path("amount_minor").asLong()).isEqualTo(6_000);
    assertThat(finalPayment.path("voided_minor").asLong()).isEqualTo(6_000);
    assertThat(finalPayment.path("captured_minor").asLong()).isEqualTo(4_000);
    assertThat(finalPayment.path("refunded_minor").asLong()).isEqualTo(1_000);

    Response fetched = api.get("/v1/payments/" + id, merchant);
    assertThat(fetched.status()).isEqualTo(200);
    assertThat(fetched.json().path("voided_minor").asLong()).isEqualTo(6_000);

    JsonNode ledger = api.get("/v1/payments/" + id + "/ledger", merchant).json();
    assertThat(ledger.path("transactions")).hasSize(2); // capture and refund; void moves no money
    long debits = 0;
    long credits = 0;
    for (JsonNode tx : ledger.path("transactions")) {
      long txDebits = 0;
      long txCredits = 0;
      for (JsonNode entry : tx.path("entries")) {
        long amount = entry.path("amount_minor").asLong();
        if (entry.path("direction").asText().equals("DEBIT")) {
          txDebits += amount;
        } else {
          txCredits += amount;
        }
      }
      assertThat(txDebits).isEqualTo(txCredits);
      debits += txDebits;
      credits += txCredits;
    }
    assertThat(debits).isEqualTo(credits).isEqualTo(5_000); // 4000 capture + 1000 refund
  }

  @Test
  void captureLedgerSplitsTheFee() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 10_000, "USD");
    api.capture(merchant, newKey(), id, 10_000, "USD");

    JsonNode tx =
        api.get("/v1/payments/" + id + "/ledger", merchant).json().path("transactions").get(0);
    assertThat(tx.path("entries")).hasSize(3);
    long payable = 0;
    long fee = 0;
    long clearing = 0;
    for (JsonNode e : tx.path("entries")) {
      switch (e.path("account_type").asText()) {
        case "MERCHANT_PAYABLE" -> payable = e.path("amount_minor").asLong();
        case "FEE_REVENUE" -> fee = e.path("amount_minor").asLong();
        case "PSP_CLEARING" -> clearing = e.path("amount_minor").asLong();
        default -> throw new AssertionError("unexpected account " + e);
      }
    }
    assertThat(clearing).isEqualTo(10_000);
    assertThat(fee).isEqualTo(250);
    assertThat(payable).isEqualTo(9_750);
  }

  @Test
  void fullCaptureThenFullRefundEndsRefunded() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 500, "EUR");
    assertThat(api.capture(merchant, newKey(), id, 500, "EUR").status()).isEqualTo(201);
    Response refund = api.refund(merchant, newKey(), id, 500, "EUR");
    assertThat(refund.json().path("payment").path("state").asText()).isEqualTo("REFUNDED");
  }

  @Test
  void aDeclinedAuthorizationIsA402AndCreatesNoPayment() {
    String merchant = newMerchant();
    Response declined =
        api()
            .post(
                "/v1/payments",
                merchant,
                newKey(),
                "{\"amount_minor\": 100, \"currency\": \"USD\", \"payment_method_token\": \"tok_decline\"}");
    assertThat(declined.status()).isEqualTo(402);
    assertThat(declined.code()).isEqualTo("PAYMENT_DECLINED");
    assertThat(declined.header("Content-Type")).startsWith("application/problem+json");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE merchant_id = ?", Long.class, merchant))
        .isZero();
  }

  @Test
  void aPspOutageIsARetryable502AndCreatesNothing() {
    String merchant = newMerchant();
    Response outage =
        api()
            .post(
                "/v1/payments",
                merchant,
                newKey(),
                "{\"amount_minor\": 100, \"currency\": \"USD\", \"payment_method_token\": \"tok_psp_down\"}");
    assertThat(outage.status()).isEqualTo(502);
    assertThat(outage.code()).isEqualTo("PSP_UNAVAILABLE");
    assertThat(outage.header("Retry-After")).isEqualTo("1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE merchant_id = ?",
                Long.class,
                merchant))
        .isZero();
  }

  @Test
  void paymentsAreInvisibleToOtherMerchants() {
    Api api = api();
    String owner = newMerchant();
    String id = api.authorizeOk(owner, 100, "USD");
    String intruder = newMerchant();
    assertThat(api.get("/v1/payments/" + id, intruder).status()).isEqualTo(404);
    assertThat(api.get("/v1/payments/" + id + "/ledger", intruder).status()).isEqualTo(404);
    Response capture = api.capture(intruder, newKey(), id, 100, "USD");
    assertThat(capture.status()).isEqualTo(404);
    assertThat(capture.code()).isEqualTo("PAYMENT_NOT_FOUND");
    assertThat(api.get("/v1/payments/" + id, owner).json().path("captured_minor").asLong())
        .isZero();
  }

  @Test
  void errorsUseProblemJsonWithStableCodesAndNoStackTraces() {
    Response notFound = api().get("/v1/payments/" + java.util.UUID.randomUUID(), newMerchant());
    assertThat(notFound.status()).isEqualTo(404);
    assertThat(notFound.header("Content-Type")).startsWith("application/problem+json");
    JsonNode problem = notFound.json();
    assertThat(problem.path("type").asText()).isEqualTo("urn:payflow:problem:payment-not-found");
    assertThat(problem.path("title").asText()).isNotBlank();
    assertThat(problem.path("status").asInt()).isEqualTo(404);
    assertThat(problem.path("code").asText()).isEqualTo("PAYMENT_NOT_FOUND");
    assertThat(problem.path("instance").asText()).startsWith("/v1/payments/");
    assertThat(notFound.body()).doesNotContain("Exception").doesNotContain("\tat ");
  }

  @Test
  void unknownRoutesAndMethodsGetProblemJson() {
    Response missing = api().get("/v1/nothing-here", newMerchant());
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.code()).isEqualTo("NOT_FOUND");
    Response wrongMethod =
        api().post("/v1/payments/" + java.util.UUID.randomUUID(), newMerchant(), newKey(), "{}");
    assertThat(wrongMethod.status()).isEqualTo(405);
    assertThat(wrongMethod.code()).isEqualTo("METHOD_NOT_ALLOWED");
  }

  @Test
  void correlationIdIsEchoedOrGenerated() {
    Response generated = api().get("/v1/payments/" + java.util.UUID.randomUUID(), newMerchant());
    assertThat(generated.header("X-Correlation-Id")).isNotBlank();
    Response echoed =
        api()
            .send(
                java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(
                        "http://localhost:"
                            + port
                            + "/v1/payments/"
                            + java.util.UUID.randomUUID())),
                newMerchant(),
                null,
                java.util.Map.of("X-Correlation-Id", "trace-abc.123"));
    assertThat(echoed.header("X-Correlation-Id")).isEqualTo("trace-abc.123");
    Response unsafe =
        api()
            .send(
                java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(
                        "http://localhost:"
                            + port
                            + "/v1/payments/"
                            + java.util.UUID.randomUUID())),
                newMerchant(),
                null,
                java.util.Map.of("X-Correlation-Id", "bad value with spaces"));
    assertThat(unsafe.header("X-Correlation-Id")).isNotEqualTo("bad value with spaces");
  }
}
