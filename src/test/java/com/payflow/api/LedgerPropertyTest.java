package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.domain.DomainViolation;
import com.payflow.domain.OperationType;
import com.payflow.domain.Payment;
import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.LedgerAssertions;
import com.payflow.support.SharedApp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * Model-based property: for any random sequence of operations sent through the real HTTP API into
 * real PostgreSQL, the API accepts exactly what the pure domain model accepts, and afterwards the
 * counters, operations and ledger agree and stay within bounds.
 */
class LedgerPropertyTest {

  record Step(OperationType type, long amount) {}

  @Provide
  Arbitrary<List<Step>> steps() {
    return Combinators.combine(
            Arbitraries.of(OperationType.CAPTURE, OperationType.REFUND, OperationType.VOID),
            Arbitraries.longs().between(1, 250))
        .as(Step::new)
        .list()
        .ofMinSize(1)
        .ofMaxSize(12);
  }

  @Property(tries = 40)
  void apiMatchesTheModelAndTheLedgerStaysBalanced(
      @ForAll @LongRange(min = 1, max = 600) long authorized, @ForAll("steps") List<Step> steps) {
    Api api = SharedApp.api();
    String merchant = "m_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    Response created = api.authorize(merchant, UUID.randomUUID().toString(), authorized, "USD");
    assertThat(created.status()).isEqualTo(201);
    String id = created.json().path("id").asText();
    Payment model =
        Payment.authorize(
            UUID.fromString(id), merchant, authorized, "USD", "psp", null, Instant.now());

    for (Step step : steps) {
      String key = UUID.randomUUID().toString();
      Payment expected = null;
      try {
        expected =
            switch (step.type()) {
              case CAPTURE -> model.capture(step.amount(), "USD", Instant.now());
              case REFUND -> model.refund(step.amount(), "USD", Instant.now());
              case VOID -> model.voidRemainder(Instant.now());
              case AUTHORIZE -> throw new AssertionError();
            };
      } catch (DomainViolation rejected) {
        // expected stays null
      }
      Response response =
          switch (step.type()) {
            case CAPTURE -> api.capture(merchant, key, id, step.amount(), "USD");
            case REFUND -> api.refund(merchant, key, id, step.amount(), "USD");
            case VOID -> api.voidPayment(merchant, key, id);
            case AUTHORIZE -> throw new AssertionError();
          };
      if (expected == null) {
        assertThat(response.status())
            .as("%s %d should be rejected", step.type(), step.amount())
            .isEqualTo(409);
      } else {
        assertThat(response.status())
            .as("%s %d should be accepted", step.type(), step.amount())
            .isEqualTo(201);
        model = expected;
      }
    }

    Response stored = api.get("/v1/payments/" + id, merchant);
    assertThat(stored.json().path("captured_minor").asLong()).isEqualTo(model.capturedMinor());
    assertThat(stored.json().path("refunded_minor").asLong()).isEqualTo(model.refundedMinor());
    assertThat(stored.json().path("voided_minor").asLong()).isEqualTo(model.voidedMinor());
    assertThat(stored.json().path("state").asText()).isEqualTo(model.state().name());
    assertThat(model.capturedMinor() + model.voidedMinor()).isLessThanOrEqualTo(authorized);
    assertThat(model.refundedMinor()).isLessThanOrEqualTo(model.capturedMinor());
    LedgerAssertions.assertConsistent(SharedApp.jdbc(), id);
  }
}
