package com.payflow.infrastructure;

import com.payflow.domain.Direction;
import com.payflow.domain.LedgerAccountType;
import com.payflow.domain.LedgerPostings.EntryDraft;
import com.payflow.domain.LedgerTransactionView;
import com.payflow.domain.LedgerTransactionView.LedgerEntryView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

  public static final String PLATFORM_SCOPE = "*";

  /** One ledger line of one payment, as needed by reconciliation. */
  public record PaymentEntry(
      UUID paymentId, LedgerAccountType accountType, Direction direction, long amountMinor) {}

  private final JdbcClient jdbc;

  public LedgerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Posts one balanced transaction. The database re-verifies the balance at commit. */
  public void post(
      UUID transactionId,
      UUID paymentId,
      UUID operationId,
      String description,
      String merchantId,
      String currency,
      List<EntryDraft> entries,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO ledger_transactions (id, payment_id, operation_id, description, created_at)
            VALUES (:id, :payment, :operation, :description, :createdAt)
            """)
        .param("id", transactionId)
        .param("payment", paymentId)
        .param("operation", operationId)
        .param("description", description)
        .param("createdAt", Db.ts(now))
        .update();
    for (EntryDraft entry : entries) {
      UUID accountId = accountId(entry.account(), merchantId, currency);
      jdbc.sql(
              """
              INSERT INTO ledger_entries (transaction_id, account_id, direction, amount_minor, created_at)
              VALUES (:tx, :account, :direction, :amount, :createdAt)
              """)
          .param("tx", transactionId)
          .param("account", accountId)
          .param("direction", entry.direction().name())
          .param("amount", entry.amountMinor())
          .param("createdAt", Db.ts(now))
          .update();
    }
  }

  /** Looks an account up, creating it on first use. */
  UUID accountId(LedgerAccountType type, String merchantId, String currency) {
    String scope = type.merchantScoped() ? merchantId : PLATFORM_SCOPE;
    Optional<UUID> existing = find(type, scope, currency);
    if (existing.isPresent()) {
      return existing.get();
    }
    jdbc.sql(
            """
            INSERT INTO ledger_accounts (id, type, merchant_id, currency)
            VALUES (:id, :type, :merchant, :currency)
            ON CONFLICT (type, merchant_id, currency) DO NOTHING
            """)
        .param("id", UUID.randomUUID())
        .param("type", type.name())
        .param("merchant", scope)
        .param("currency", currency)
        .update();
    return find(type, scope, currency)
        .orElseThrow(
            () ->
                new TransientConflictException(
                    "ledger account created concurrently; retry with a fresh snapshot", null));
  }

  private Optional<UUID> find(LedgerAccountType type, String scope, String currency) {
    return jdbc.sql(
            "SELECT id FROM ledger_accounts WHERE type = :type AND merchant_id = :merchant"
                + " AND currency = :currency")
        .param("type", type.name())
        .param("merchant", scope)
        .param("currency", currency)
        .query(UUID.class)
        .optional();
  }

  /** All transactions of a payment with their entries in a single round trip. */
  public List<LedgerTransactionView> findByPayment(UUID paymentId) {
    record Row(
        UUID txId,
        UUID operationId,
        String description,
        Instant createdAt,
        LedgerEntryView entry) {}
    List<Row> rows =
        jdbc.sql(
                """
                SELECT t.id AS tx_id, t.operation_id, t.description, t.created_at,
                       e.account_id, a.type AS account_type, e.direction, e.amount_minor
                  FROM ledger_transactions t
                  JOIN ledger_entries e ON e.transaction_id = t.id
                  JOIN ledger_accounts a ON a.id = e.account_id
                 WHERE t.payment_id = :payment
                 ORDER BY t.created_at, t.id, e.id
                """)
            .param("payment", paymentId)
            .query(
                (rs, n) ->
                    new Row(
                        Db.uuid(rs, "tx_id"),
                        Db.uuid(rs, "operation_id"),
                        rs.getString("description"),
                        Db.instant(rs, "created_at"),
                        new LedgerEntryView(
                            Db.uuid(rs, "account_id"),
                            LedgerAccountType.valueOf(rs.getString("account_type")),
                            Direction.valueOf(rs.getString("direction")),
                            rs.getLong("amount_minor"))))
            .list();
    Map<UUID, Row> headers = new LinkedHashMap<>();
    Map<UUID, List<LedgerEntryView>> entries = new LinkedHashMap<>();
    for (Row row : rows) {
      entries.computeIfAbsent(row.txId(), k -> new ArrayList<>()).add(row.entry());
      headers.putIfAbsent(row.txId(), row);
    }
    List<LedgerTransactionView> result = new ArrayList<>();
    headers.forEach(
        (txId, header) ->
            result.add(
                new LedgerTransactionView(
                    txId,
                    header.operationId(),
                    header.description(),
                    header.createdAt(),
                    entries.get(txId))));
    return result;
  }

  public List<PaymentEntry> entriesForPayments(Collection<UUID> paymentIds) {
    return jdbc.sql(
            """
            SELECT t.payment_id, a.type AS account_type, e.direction, e.amount_minor
              FROM ledger_transactions t
              JOIN ledger_entries e ON e.transaction_id = t.id
              JOIN ledger_accounts a ON a.id = e.account_id
             WHERE t.payment_id = ANY(:ids)
            """)
        .param("ids", paymentIds.toArray(UUID[]::new))
        .query(
            (rs, n) ->
                new PaymentEntry(
                    Db.uuid(rs, "payment_id"),
                    LedgerAccountType.valueOf(rs.getString("account_type")),
                    Direction.valueOf(rs.getString("direction")),
                    rs.getLong("amount_minor")))
        .list();
  }
}
