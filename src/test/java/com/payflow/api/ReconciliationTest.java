package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.payflow.application.ReconciliationService;
import com.payflow.application.WebhookSignature;
import com.payflow.infrastructure.ReconciliationRepository.Run;
import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationTest extends IntegrationTest {

  private static final String ADMIN_TOKEN = "test-admin-token-0123456789abcdef";

  @Autowired ReconciliationService reconciliation;

  private Response admin(String path, String token) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    return api()
        .send(builder.GET(), null, null, token == null ? Map.of() : Map.of("X-Admin-Token", token));
  }

  private List<JsonNode> discrepanciesFor(Run run, String paymentId) {
    JsonNode all =
        admin("/v1/reconciliation/runs/" + run.id() + "/discrepancies", ADMIN_TOKEN).json();
    List<JsonNode> mine = new ArrayList<>();
    all.forEach(
        d -> {
          if (d.path("payment_id").asText().equals(paymentId)) {
            mine.add(d);
          }
        });
    return mine;
  }

  private String healthyPayment(Api api, String merchant) {
    String id = api.authorizeOk(merchant, 10_000, "USD");
    api.capture(merchant, newKey(), id, 6_000, "USD");
    api.refund(merchant, newKey(), id, 1_000, "USD");
    api.voidPayment(merchant, newKey(), id);
    return id;
  }

  @Test
  void consistentPaymentsProduceNoDiscrepancies() {
    Api api = api();
    String merchant = newMerchant();
    String healthy = healthyPayment(api, merchant);
    String untouched = api.authorizeOk(merchant, 500, "USD");

    Run run = reconciliation.run();

    assertThat(run.state()).isEqualTo("COMPLETED");
    assertThat(run.paymentsChecked()).isGreaterThanOrEqualTo(2);
    assertThat(discrepanciesFor(run, healthy)).isEmpty();
    assertThat(discrepanciesFor(run, untouched)).isEmpty();
  }

  @Test
  void aSeededCounterDriftIsCaught() {
    Api api = api();
    String merchant = newMerchant();
    String id = healthyPayment(api, merchant);
    // Corrupt the stored counter behind the application's back.
    jdbc.update("UPDATE payments SET refunded_minor = 2500 WHERE id = ?::uuid", id);

    Run run = reconciliation.run();

    List<JsonNode> found = discrepanciesFor(run, id);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).path("kind").asText()).isEqualTo("AGGREGATE_MISMATCH");
    assertThat(found.get(0).path("subject").asText()).isEqualTo("refunded_minor");
    assertThat(found.get(0).path("expected_minor").asLong()).isEqualTo(1_000);
    assertThat(found.get(0).path("actual_minor").asLong()).isEqualTo(2_500);
    assertThat(run.discrepancyCount()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void aSeededLedgerDriftIsCaught() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 10_000, "USD");
    api.capture(merchant, newKey(), id, 4_000, "USD");

    // A balanced but unjustified posting (valid for the DB, wrong for the business): attach a
    // transaction to the payment's AUTHORIZE operation, which must never move money.
    UUID authorizeOp =
        jdbc.queryForObject(
            "SELECT id FROM payment_operations WHERE payment_id = ?::uuid AND type = 'AUTHORIZE'",
            UUID.class,
            id);
    UUID clearing =
        jdbc.queryForObject(
            "SELECT id FROM ledger_accounts WHERE type = 'PSP_CLEARING' AND currency = 'USD'",
            UUID.class);
    UUID payable =
        jdbc.queryForObject(
            "SELECT id FROM ledger_accounts WHERE type = 'MERCHANT_PAYABLE' AND merchant_id = ?",
            UUID.class,
            merchant);
    UUID tx = UUID.randomUUID();
    org.springframework.transaction.support.TransactionTemplate template =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
    template.executeWithoutResult(
        s -> {
          jdbc.update(
              "INSERT INTO ledger_transactions (id, payment_id, operation_id, description, created_at) VALUES (?, ?::uuid, ?, 'rogue', now())",
              tx,
              id,
              authorizeOp);
          jdbc.update(
              "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount_minor) VALUES (?, ?, 'DEBIT', 70)",
              tx,
              clearing);
          jdbc.update(
              "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount_minor) VALUES (?, ?, 'CREDIT', 70)",
              tx,
              payable);
        });

    Run run = reconciliation.run();

    List<JsonNode> found = discrepanciesFor(run, id);
    assertThat(found).hasSize(2);
    assertThat(found)
        .allSatisfy(d -> assertThat(d.path("kind").asText()).isEqualTo("LEDGER_POSITION_MISMATCH"));
    JsonNode clearingDiff =
        found.stream()
            .filter(d -> d.path("subject").asText().equals("PSP_CLEARING"))
            .findFirst()
            .orElseThrow();
    assertThat(clearingDiff.path("expected_minor").asLong()).isEqualTo(4_000);
    assertThat(clearingDiff.path("actual_minor").asLong()).isEqualTo(4_070);
    JsonNode payableDiff =
        found.stream()
            .filter(d -> d.path("subject").asText().equals("MERCHANT_PAYABLE"))
            .findFirst()
            .orElseThrow();
    assertThat(payableDiff.path("expected_minor").asLong()).isEqualTo(-3_900);
    assertThat(payableDiff.path("actual_minor").asLong()).isEqualTo(-3_970);
  }

  @Test
  void anOperationTheProviderReportedAsFailedIsFlaggedWhenTheLedgerStillCountsIt() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    api.capture(merchant, newKey(), id, 1_000, "USD");
    String reference =
        jdbc.queryForObject(
            "SELECT psp_reference FROM payment_operations WHERE payment_id = ?::uuid AND type = 'CAPTURE'",
            String.class,
            id);
    byte[] body =
        "{\"id\": \"evt_%s\", \"type\": \"operation.failed\", \"data\": {\"psp_reference\": \"%s\"}}"
            .formatted(UUID.randomUUID(), reference)
            .getBytes(StandardCharsets.UTF_8);
    Response webhook =
        api.postRaw(
            "/v1/webhooks/psp",
            Map.of(
                "Content-Type",
                "application/json",
                "PSP-Signature",
                WebhookSignature.header(
                    "test-psp-webhook-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                    Instant.now(),
                    body)),
            body);
    assertThat(webhook.status()).isEqualTo(200);

    Run run = reconciliation.run();

    List<JsonNode> found = discrepanciesFor(run, id);
    assertThat(found)
        .extracting(d -> d.path("subject").asText())
        .contains("captured_minor", "PSP_CLEARING");
  }

  @Test
  void theDiscrepancyEndpointsRequireTheAdminToken() {
    Run run = reconciliation.run();
    String path = "/v1/reconciliation/runs/" + run.id();
    for (String p : new String[] {"/v1/reconciliation/runs", path, path + "/discrepancies"}) {
      assertThat(admin(p, null).status()).as(p).isEqualTo(401);
      assertThat(admin(p, "wrong-token").status()).isEqualTo(401);
      assertThat(admin(p, ADMIN_TOKEN).status()).isEqualTo(200);
    }
    assertThat(admin(path, null).code()).isEqualTo("UNAUTHORIZED");
  }

  @Test
  void runsAreListedNewestFirstAndDetailsCanBeFetched() {
    Run first = reconciliation.run();
    Run second = reconciliation.run();

    JsonNode runs = admin("/v1/reconciliation/runs?limit=2", ADMIN_TOKEN).json();
    assertThat(runs).hasSize(2);
    assertThat(runs.get(0).path("id").asText()).isEqualTo(second.id().toString());
    assertThat(runs.get(1).path("id").asText()).isEqualTo(first.id().toString());
    assertThat(runs.get(0).path("state").asText()).isEqualTo("COMPLETED");

    JsonNode detail = admin("/v1/reconciliation/runs/" + first.id(), ADMIN_TOKEN).json();
    assertThat(detail.path("run").path("id").asText()).isEqualTo(first.id().toString());
    assertThat(detail.path("discrepancies").isArray()).isTrue();

    assertThat(admin("/v1/reconciliation/runs/" + UUID.randomUUID(), ADMIN_TOKEN).status())
        .isEqualTo(404);
    assertThat(admin("/v1/reconciliation/runs/not-a-uuid", ADMIN_TOKEN).status()).isEqualTo(404);
    assertThat(admin("/v1/reconciliation/runs?limit=0", ADMIN_TOKEN).json()).hasSize(1);
  }
}
