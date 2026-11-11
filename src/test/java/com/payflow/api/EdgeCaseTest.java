package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.IntegrationTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EdgeCaseTest extends IntegrationTest {

  private Response authorizeBody(String merchant, String body) {
    return api().post("/v1/payments", merchant, newKey(), body);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, -9_223_372_036_854_775_807L})
  void zeroAndNegativeAmountsAreRejectedOnEveryEndpoint(long amount) {
    Api api = api();
    String merchant = newMerchant();
    Response authorize = api.authorize(merchant, newKey(), amount, "USD");
    assertThat(authorize.status()).isEqualTo(400);
    assertThat(authorize.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(authorize.json().path("errors").get(0).path("field").asText())
        .isEqualTo("amount_minor");

    String id = api.authorizeOk(merchant, 1_000, "USD");
    assertThat(api.capture(merchant, newKey(), id, amount, "USD").code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(api.refund(merchant, newKey(), id, amount, "USD").code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void longMaxValueAuthorizationCannotBeOverCapturedByOverflowing() {
    Api api = api();
    String merchant = newMerchant();
    Response authorized = api.authorize(merchant, newKey(), Long.MAX_VALUE, "USD");
    assertThat(authorized.status()).isEqualTo(201);
    String id = authorized.json().path("id").asText();

    assertThat(api.capture(merchant, newKey(), id, Long.MAX_VALUE - 1, "USD").status())
        .isEqualTo(201);
    Response over = api.capture(merchant, newKey(), id, 2, "USD");
    assertThat(over.status()).isEqualTo(409);
    assertThat(over.code()).isEqualTo("AMOUNT_EXCEEDS_CAPTURABLE");
    Response overMax = api.capture(merchant, newKey(), id, Long.MAX_VALUE, "USD");
    assertThat(overMax.code()).isEqualTo("AMOUNT_EXCEEDS_CAPTURABLE");
    Response last = api.capture(merchant, newKey(), id, 1, "USD");
    assertThat(last.status()).isEqualTo(201);
    assertThat(last.json().path("payment").path("captured_minor").asLong())
        .isEqualTo(Long.MAX_VALUE);

    Response refund = api.refund(merchant, newKey(), id, Long.MAX_VALUE, "USD");
    assertThat(refund.status()).isEqualTo(201);
    assertThat(refund.json().path("payment").path("state").asText()).isEqualTo("REFUNDED");
    assertThat(api.get("/v1/payments/" + id + "/ledger", merchant).status()).isEqualTo(200);
  }

  @Test
  void amountsBeyondLongAreRejectedAsBadRequestNotTruncated() {
    String merchant = newMerchant();
    Response tooBig =
        authorizeBody(
            merchant,
            "{\"amount_minor\": 9223372036854775808, \"currency\": \"USD\", \"payment_method_token\": \"t\"}");
    assertThat(tooBig.status()).isEqualTo(400);
  }

  @Test
  void fractionalAndQuotedAmountsAreNotCoerced() {
    String merchant = newMerchant();
    for (String amount : new String[] {"10.5", "10.0", "\"100\"", "true", "[]", "{}", "1e3"}) {
      Response r =
          authorizeBody(
              merchant,
              "{\"amount_minor\": %s, \"currency\": \"USD\", \"payment_method_token\": \"t\"}"
                  .formatted(amount));
      assertThat(r.status()).as("amount %s", amount).isEqualTo(400);
    }
  }

  @Test
  void currencyMismatchBetweenAuthorizeAndCaptureOrRefundIsRejected() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    Response capture = api.capture(merchant, newKey(), id, 100, "EUR");
    assertThat(capture.status()).isEqualTo(422);
    assertThat(capture.code()).isEqualTo("CURRENCY_MISMATCH");
    api.capture(merchant, newKey(), id, 100, "USD");
    Response refund = api.refund(merchant, newKey(), id, 50, "EUR");
    assertThat(refund.code()).isEqualTo("CURRENCY_MISMATCH");
    assertThat(api.get("/v1/payments/" + id, merchant).json().path("captured_minor").asLong())
        .isEqualTo(100);
  }

  @Test
  void unsupportedAndMalformedCurrenciesAreDistinguished() {
    Api api = api();
    String merchant = newMerchant();
    Response unsupported = api.authorize(merchant, newKey(), 100, "XXX");
    assertThat(unsupported.status()).isEqualTo(422);
    assertThat(unsupported.code()).isEqualTo("UNSUPPORTED_CURRENCY");
    for (String bad : new String[] {"usd", "US", "USDD", "U1D", ""}) {
      Response r = api.authorize(merchant, newKey(), 100, bad);
      assertThat(r.status()).as("currency '%s'", bad).isEqualTo(400);
      assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    }
  }

  @Test
  void unknownAndMalformedPaymentIdsAreDistinguished() {
    Api api = api();
    String merchant = newMerchant();
    Response unknown =
        api.capture(merchant, newKey(), "0198a000-0000-7000-8000-000000000000", 1, "USD");
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(unknown.code()).isEqualTo("PAYMENT_NOT_FOUND");
    for (String malformed :
        new String[] {"not-a-uuid", "123", "1-1-1-1-1", "0198a000-0000-7000-8000-00000000000g"}) {
      Response capture = api.capture(merchant, newKey(), malformed, 1, "USD");
      assertThat(capture.status()).as(malformed).isEqualTo(400);
      assertThat(capture.code()).isEqualTo("INVALID_PAYMENT_ID");
      assertThat(api.get("/v1/payments/" + malformed, merchant).code())
          .isEqualTo("INVALID_PAYMENT_ID");
      assertThat(api.get("/v1/payments/" + malformed + "/ledger", merchant).code())
          .isEqualTo("INVALID_PAYMENT_ID");
      assertThat(api.voidPayment(merchant, newKey(), malformed).code())
          .isEqualTo("INVALID_PAYMENT_ID");
      assertThat(api.refund(merchant, newKey(), malformed, 1, "USD").code())
          .isEqualTo("INVALID_PAYMENT_ID");
    }
  }

  @Test
  void refundExceedingCapturedAfterPartialCaptureIsRejected() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    api.capture(merchant, newKey(), id, 300, "USD");
    Response over = api.refund(merchant, newKey(), id, 301, "USD");
    assertThat(over.status()).isEqualTo(409);
    assertThat(over.code()).isEqualTo("AMOUNT_EXCEEDS_REFUNDABLE");
    assertThat(api.refund(merchant, newKey(), id, 300, "USD").status()).isEqualTo(201);
    assertThat(api.refund(merchant, newKey(), id, 1, "USD").code())
        .isEqualTo("AMOUNT_EXCEEDS_REFUNDABLE");
  }

  @Test
  void partialCapturesSummingExactlyToTheAuthorizationSucceedAndOneMinorUnitOverFails() {
    Api api = api();
    String merchant = newMerchant();
    String exact = api.authorizeOk(merchant, 100, "USD");
    for (long part : new long[] {33, 33, 34}) {
      assertThat(api.capture(merchant, newKey(), exact, part, "USD").status()).isEqualTo(201);
    }
    assertThat(api.get("/v1/payments/" + exact, merchant).json().path("state").asText())
        .isEqualTo("CAPTURED");

    String over = api.authorizeOk(merchant, 100, "USD");
    api.capture(merchant, newKey(), over, 33, "USD");
    api.capture(merchant, newKey(), over, 33, "USD");
    Response oneOver = api.capture(merchant, newKey(), over, 35, "USD");
    assertThat(oneOver.status()).isEqualTo(409);
    assertThat(oneOver.code()).isEqualTo("AMOUNT_EXCEEDS_CAPTURABLE");
    assertThat(api.get("/v1/payments/" + over, merchant).json().path("captured_minor").asLong())
        .isEqualTo(66);
  }

  @Test
  void refundOnAVoidedPaymentAndCaptureAfterVoidAndDoubleVoidAreConflicts() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 100, "USD");
    assertThat(api.voidPayment(merchant, newKey(), id).status()).isEqualTo(201);
    assertThat(api.refund(merchant, newKey(), id, 1, "USD").code())
        .isEqualTo("INVALID_STATE_TRANSITION");
    assertThat(api.capture(merchant, newKey(), id, 1, "USD").code())
        .isEqualTo("INVALID_STATE_TRANSITION");
    Response second = api.voidPayment(merchant, newKey(), id);
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.code()).isEqualTo("INVALID_STATE_TRANSITION");
  }

  @Test
  void refundBeforeCaptureIsAConflict() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 100, "USD");
    Response r = api.refund(merchant, newKey(), id, 1, "USD");
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.code()).isEqualTo("INVALID_STATE_TRANSITION");
  }

  @Test
  void voidAfterPartialCaptureReleasesTheRemainderAndKeepsTheCapture() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 100, "USD");
    api.capture(merchant, newKey(), id, 30, "USD");
    Response voided = api.voidPayment(merchant, newKey(), id);
    assertThat(voided.status()).isEqualTo(201);
    assertThat(voided.json().path("amount_minor").asLong()).isEqualTo(70);
    assertThat(voided.json().path("payment").path("state").asText()).isEqualTo("CAPTURED");
    assertThat(api.capture(merchant, newKey(), id, 1, "USD").code())
        .isEqualTo("INVALID_STATE_TRANSITION");
    assertThat(api.refund(merchant, newKey(), id, 30, "USD").status()).isEqualTo(201);
  }

  @Test
  void aBodyAboveTheLimitIsRejectedWith413() {
    String merchant = newMerchant();
    String padding = "x".repeat(70_000);
    Response r =
        authorizeBody(
            merchant,
            "{\"amount_minor\": 100, \"currency\": \"USD\", \"payment_method_token\": \"t\", \"reference\": \""
                + padding
                + "\"}");
    assertThat(r.status()).isEqualTo(413);
    assertThat(r.code()).isEqualTo("PAYLOAD_TOO_LARGE");
    assertThat(r.header("Content-Type")).startsWith("application/problem+json");
  }

  @Test
  void aBodyExactlyAtTheLimitIsAcceptedByTheFilter() {
    String merchant = newMerchant();
    String prefix =
        "{\"amount_minor\": 100, \"currency\": \"USD\", \"payment_method_token\": \"t\", \"reference\": \"";
    String suffix = "\"}";
    String body = prefix + "x".repeat(65_536 - prefix.length() - suffix.length()) + suffix;
    assertThat(body.length()).isEqualTo(65_536);
    Response r = authorizeBody(merchant, body);
    // Passes the size filter; the reference is then rejected by Bean Validation (max 255).
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
  }

  static Stream<Arguments> invalidAuthorizeBodies() {
    return Stream.of(
        Arguments.of("{}", "amount_minor"),
        Arguments.of("{\"currency\": \"USD\", \"payment_method_token\": \"t\"}", "amount_minor"),
        Arguments.of(
            "{\"amount_minor\": null, \"currency\": \"USD\", \"payment_method_token\": \"t\"}",
            "amount_minor"),
        Arguments.of("{\"amount_minor\": 1, \"payment_method_token\": \"t\"}", "currency"),
        Arguments.of(
            "{\"amount_minor\": 1, \"currency\": null, \"payment_method_token\": \"t\"}",
            "currency"),
        Arguments.of("{\"amount_minor\": 1, \"currency\": \"USD\"}", "payment_method_token"),
        Arguments.of(
            "{\"amount_minor\": 1, \"currency\": \"USD\", \"payment_method_token\": null}",
            "payment_method_token"),
        Arguments.of(
            "{\"amount_minor\": 1, \"currency\": \"USD\", \"payment_method_token\": \"  \"}",
            "payment_method_token"),
        Arguments.of(
            "{\"amount_minor\": 1, \"currency\": \"USD\", \"payment_method_token\": \"t\", \"unexpected\": 1}",
            "unexpected"));
  }

  @ParameterizedTest
  @MethodSource("invalidAuthorizeBodies")
  void authorizeRejectsNullOrAbsentFields(String body, String field) {
    Response r = authorizeBody(newMerchant(), body);
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(r.body()).contains(field);
  }

  static Stream<Arguments> invalidMoneyBodies() {
    return Stream.of(
        Arguments.of("{}", "amount_minor"),
        Arguments.of("{\"currency\": \"USD\"}", "amount_minor"),
        Arguments.of("{\"amount_minor\": null, \"currency\": \"USD\"}", "amount_minor"),
        Arguments.of("{\"amount_minor\": 1}", "currency"),
        Arguments.of("{\"amount_minor\": 1, \"currency\": null}", "currency"),
        Arguments.of(
            "{\"amount_minor\": 1, \"currency\": \"USD\", \"reason\": \"" + "r".repeat(256) + "\"}",
            "reason"));
  }

  @ParameterizedTest
  @MethodSource("invalidMoneyBodies")
  void captureAndRefundRejectNullOrAbsentFields(String body, String field) {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 100, "USD");
    for (String endpoint : new String[] {"captures", "refunds"}) {
      Response r = api.post("/v1/payments/" + id + "/" + endpoint, merchant, newKey(), body);
      assertThat(r.status()).as(endpoint).isEqualTo(400);
      assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
      assertThat(r.body()).contains(field);
    }
  }

  @Test
  void voidAcceptsAnEmptyBodyOrEmptyObjectButNotGarbageOrUnknownFields() {
    Api api = api();
    String merchant = newMerchant();
    String a = api.authorizeOk(merchant, 100, "USD");
    assertThat(api.post("/v1/payments/" + a + "/voids", merchant, newKey(), null).status())
        .isEqualTo(201);
    String b = api.authorizeOk(merchant, 100, "USD");
    assertThat(api.post("/v1/payments/" + b + "/voids", merchant, newKey(), "{}").status())
        .isEqualTo(201);
    String c = api.authorizeOk(merchant, 100, "USD");
    assertThat(
            api.post("/v1/payments/" + c + "/voids", merchant, newKey(), "{\"reason\": \"dup\"}")
                .status())
        .isEqualTo(201);
    String d = api.authorizeOk(merchant, 100, "USD");
    assertThat(
            api.post("/v1/payments/" + d + "/voids", merchant, newKey(), "{\"nope\": 1}").status())
        .isEqualTo(400);
    assertThat(api.post("/v1/payments/" + d + "/voids", merchant, newKey(), "not json").code())
        .isEqualTo("MALFORMED_REQUEST");
    assertThat(
            api.post("/v1/payments/" + d + "/voids", merchant, newKey(), "{\"reason\": 7}").code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(api.get("/v1/payments/" + d, merchant).json().path("state").asText())
        .isEqualTo("AUTHORIZED");
  }

  @Test
  void bodiesThatAreNotObjectsOrNotJsonAreRejected() {
    String merchant = newMerchant();
    for (String body :
        new String[] {
          "",
          "null",
          "[]",
          "\"str\"",
          "42",
          "{",
          "{\"a\":1}{\"b\":2}",
          "{\"amount_minor\":1,\"amount_minor\":2}"
        }) {
      Response r = authorizeBody(merchant, body);
      assertThat(r.status()).as("body '%s'", body).isEqualTo(400);
      assertThat(r.header("Content-Type")).startsWith("application/problem+json");
    }
  }

  @Test
  void nonJsonContentTypeIsRejectedWith415() {
    Response r =
        api()
            .postRaw(
                "/v1/payments",
                Map.of(
                    "X-Merchant-Id",
                    newMerchant(),
                    "Idempotency-Key",
                    newKey(),
                    "Content-Type",
                    "text/plain"),
                "{\"amount_minor\": 1, \"currency\": \"USD\", \"payment_method_token\": \"t\"}"
                    .getBytes());
    assertThat(r.status()).isEqualTo(415);
    assertThat(r.code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void merchantHeaderIsRequiredAndValidated() {
    Api api = api();
    Response missing = api.post("/v1/payments", null, newKey(), "{}");
    assertThat(missing.status()).isEqualTo(400);
    assertThat(missing.code()).isEqualTo("MISSING_MERCHANT_ID");
    assertThat(api.get("/v1/payments/" + java.util.UUID.randomUUID(), null).code())
        .isEqualTo("MISSING_MERCHANT_ID");
    assertThat(api.get("/v1/payments/" + java.util.UUID.randomUUID(), "bad merchant!").code())
        .isEqualTo("MISSING_MERCHANT_ID");
  }
}
