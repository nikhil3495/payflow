package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.application.PspGateway;
import com.payflow.infrastructure.SimulatedPspGateway;
import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.IntegrationTest;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.CannotAcquireLockException;

/**
 * A key whose request failed with a 5xx or crashed mid-flight must stay retryable, and a request
 * that loses repeated serialization races must surface as a 503, never a 500.
 */
@Import(IdempotencyFailureTest.FlakyPspConfig.class)
class IdempotencyFailureTest extends IntegrationTest {

  enum Fault {
    NONE,
    CRASH_AFTER_PSP_CALL,
    SERIALIZATION_CONFLICT
  }

  static final class FlakyPsp implements PspGateway {
    final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
    final AtomicInteger captureCalls = new AtomicInteger();
    private final PspGateway delegate = new SimulatedPspGateway();

    @Override
    public String authorize(String op, String token, long amount, String currency) {
      return delegate.authorize(op, token, amount, currency);
    }

    @Override
    public String capture(String op, String authorization, long amount) {
      captureCalls.incrementAndGet();
      switch (fault.get()) {
        case CRASH_AFTER_PSP_CALL -> throw new IllegalStateException("simulated crash mid-flight");
        case SERIALIZATION_CONFLICT ->
            throw new CannotAcquireLockException(
                "simulated",
                new SQLException("could not serialize access due to concurrent update", "40001"));
        case NONE -> {}
      }
      return delegate.capture(op, authorization, amount);
    }

    @Override
    public String refund(String op, String authorization, long amount) {
      return delegate.refund(op, authorization, amount);
    }

    @Override
    public String voidAuthorization(String op, String authorization) {
      return delegate.voidAuthorization(op, authorization);
    }
  }

  @TestConfiguration
  static class FlakyPspConfig {
    @Bean
    @Primary
    FlakyPsp flakyPsp() {
      return new FlakyPsp();
    }
  }

  @Autowired FlakyPsp psp;

  @BeforeEach
  void reset() {
    psp.fault.set(Fault.NONE);
    psp.captureCalls.set(0);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  @Test
  void aCrashMidFlightRollsEverythingBackAndTheKeyStaysRetryable() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");

    psp.fault.set(Fault.CRASH_AFTER_PSP_CALL);
    Response crashed = api.capture(merchant, "crash-key", id, 400, "USD");
    assertThat(crashed.status()).isEqualTo(500);
    assertThat(crashed.code()).isEqualTo("INTERNAL_ERROR");
    assertThat(crashed.body()).doesNotContain("simulated crash").doesNotContain("Exception");
    assertThat(
            count(
                "SELECT count(*) FROM idempotency_keys WHERE merchant_id = ? AND idempotency_key = 'crash-key'",
                merchant))
        .isZero();
    assertThat(api.get("/v1/payments/" + id, merchant).json().path("captured_minor").asLong())
        .isZero();
    assertThat(count("SELECT count(*) FROM payment_operations WHERE payment_id = ?::uuid", id))
        .isEqualTo(1); // only the authorization

    psp.fault.set(Fault.NONE);
    Response retried = api.capture(merchant, "crash-key", id, 400, "USD");
    assertThat(retried.status()).isEqualTo(201);
    assertThat(retried.header("Idempotency-Replayed")).isNull();
    assertThat(api.get("/v1/payments/" + id, merchant).json().path("captured_minor").asLong())
        .isEqualTo(400);

    Response replay = api.capture(merchant, "crash-key", id, 400, "USD");
    assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
    assertThat(replay.body()).isEqualTo(retried.body());
    assertThat(psp.captureCalls.get()).isEqualTo(2);
  }

  @Test
  void aPspOutageDoesNotPoisonTheKey() {
    Api api = api();
    String merchant = newMerchant();
    String body =
        "{\"amount_minor\": 100, \"currency\": \"USD\", \"payment_method_token\": \"tok_psp_down\"}";
    Response outage = api.post("/v1/payments", merchant, "outage-key", body);
    assertThat(outage.status()).isEqualTo(502);

    // Same key, now with a working token: not a "key reused" error, because nothing was stored.
    Response ok =
        api.post(
            "/v1/payments",
            merchant,
            "outage-key",
            "{\"amount_minor\": 100, \"currency\": \"USD\", \"payment_method_token\": \"tok_visa\"}");
    assertThat(ok.status()).isEqualTo(201);
  }

  @Test
  void exhaustedSerializationRetriesAreA503WithRetryAfterAndLeaveNoTrace() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");

    psp.fault.set(Fault.SERIALIZATION_CONFLICT);
    Response busy = api.capture(merchant, "busy-key", id, 100, "USD");
    assertThat(busy.status()).isEqualTo(503);
    assertThat(busy.code()).isEqualTo("SERVICE_BUSY");
    assertThat(busy.header("Retry-After")).isEqualTo("1");
    assertThat(psp.captureCalls.get())
        .isEqualTo(12); // payflow.retry.max-attempts in the test profile
    assertThat(
            count(
                "SELECT count(*) FROM idempotency_keys WHERE merchant_id = ? AND idempotency_key = 'busy-key'",
                merchant))
        .isZero();

    psp.fault.set(Fault.NONE);
    assertThat(api.capture(merchant, "busy-key", id, 100, "USD").status()).isEqualTo(201);
  }
}
