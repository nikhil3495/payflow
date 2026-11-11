package com.payflow.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Cross-checks a payment's counters, operations and ledger against each other using raw SQL. */
public final class LedgerAssertions {

  private LedgerAssertions() {}

  public static void assertConsistent(JdbcTemplate jdbc, String paymentId) {
    UUID id = UUID.fromString(paymentId);

    Map<String, Object> payment =
        jdbc.queryForMap(
            "SELECT authorized_minor, captured_minor, refunded_minor, voided_minor FROM payments WHERE id = ?",
            id);
    long authorized = (Long) payment.get("authorized_minor");
    long captured = (Long) payment.get("captured_minor");
    long refunded = (Long) payment.get("refunded_minor");
    long voided = (Long) payment.get("voided_minor");
    assertThat(captured + voided).isLessThanOrEqualTo(authorized);
    assertThat(refunded).isLessThanOrEqualTo(captured);

    // Counters equal what the operations say (NUMERIC sums so the check itself cannot overflow).
    for (String type : List.of("CAPTURE", "REFUND", "VOID")) {
      Long sum =
          jdbc.queryForObject(
              "SELECT COALESCE(SUM(amount_minor), 0)::bigint FROM payment_operations WHERE payment_id = ? AND type = ?",
              Long.class,
              id,
              type);
      long expected =
          switch (type) {
            case "CAPTURE" -> captured;
            case "REFUND" -> refunded;
            default -> voided;
          };
      assertThat(sum).as("%s operations vs counter", type).isEqualTo(expected);
    }

    // Every ledger transaction balances.
    List<Map<String, Object>> unbalanced =
        jdbc.queryForList(
            """
            SELECT t.id FROM ledger_transactions t JOIN ledger_entries e ON e.transaction_id = t.id
             WHERE t.payment_id = ?
             GROUP BY t.id
            HAVING COALESCE(SUM(e.amount_minor) FILTER (WHERE e.direction = 'DEBIT'), 0)
                <> COALESCE(SUM(e.amount_minor) FILTER (WHERE e.direction = 'CREDIT'), 0)
            """,
            id);
    assertThat(unbalanced).as("unbalanced ledger transactions").isEmpty();

    // The ledger agrees with the counters: clearing = captured, receivable = refunded.
    assertThat(netDebit(jdbc, id, "PSP_CLEARING")).isEqualTo(captured);
    assertThat(netDebit(jdbc, id, "MERCHANT_RECEIVABLE")).isEqualTo(refunded);
    assertThat(netDebit(jdbc, id, "REFUND_LIABILITY")).isEqualTo(-refunded);
    assertThat(netDebit(jdbc, id, "MERCHANT_PAYABLE") + netDebit(jdbc, id, "FEE_REVENUE"))
        .isEqualTo(-captured);

    // One ledger transaction per captured/refunded operation, none for voids or authorizations.
    Long postings =
        jdbc.queryForObject(
            "SELECT count(*) FROM ledger_transactions WHERE payment_id = ?", Long.class, id);
    Long moneyOps =
        jdbc.queryForObject(
            "SELECT count(*) FROM payment_operations WHERE payment_id = ? AND type IN ('CAPTURE','REFUND')",
            Long.class,
            id);
    assertThat(postings).isEqualTo(moneyOps);
  }

  private static long netDebit(JdbcTemplate jdbc, UUID paymentId, String accountType) {
    Long value =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)::bigint
              FROM ledger_transactions t
              JOIN ledger_entries e ON e.transaction_id = t.id
              JOIN ledger_accounts a ON a.id = e.account_id
             WHERE t.payment_id = ? AND a.type = ?
            """,
            Long.class,
            paymentId,
            accountType);
    return value == null ? 0 : value;
  }
}
