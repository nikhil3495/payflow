package com.payflow.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the ledger invariants hold in the database itself, using raw SQL that bypasses every line
 * of application code.
 */
class LedgerConstraintsTest extends IntegrationTest {

  @Autowired DataSource dataSource;

  private record Fixture(UUID paymentId, UUID operationId, UUID debitAccount, UUID creditAccount) {}

  /** Inserts a payment, an operation and two accounts, committed. */
  private Fixture fixture() throws SQLException {
    UUID payment = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    UUID debit = UUID.randomUUID();
    UUID credit = UUID.randomUUID();
    String merchant = newMerchant();
    jdbc.update(
        """
        INSERT INTO payments (id, merchant_id, amount_minor, currency, authorized_minor, state,
          psp_reference, created_at, updated_at)
        VALUES (?, ?, 1000, 'USD', 1000, 'AUTHORIZED', 'psp', now(), now())
        """,
        payment,
        merchant);
    jdbc.update(
        """
        INSERT INTO payment_operations (id, payment_id, type, amount_minor, state, psp_reference,
          created_at)
        VALUES (?, ?, 'CAPTURE', 1000, 'SUCCEEDED', 'psp', now())
        """,
        operation,
        payment);
    jdbc.update(
        "INSERT INTO ledger_accounts (id, type, merchant_id, currency) VALUES (?, 'PSP_CLEARING', ?, 'USD')",
        debit,
        merchant);
    jdbc.update(
        "INSERT INTO ledger_accounts (id, type, merchant_id, currency) VALUES (?, 'MERCHANT_PAYABLE', ?, 'USD')",
        credit,
        merchant);
    return new Fixture(payment, operation, debit, credit);
  }

  private void insertTransaction(Connection c, Fixture f, UUID txId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO ledger_transactions (id, payment_id, operation_id, description, created_at)"
                + " VALUES (?, ?, ?, 'test', ?)")) {
      ps.setObject(1, txId);
      ps.setObject(2, f.paymentId());
      ps.setObject(3, f.operationId());
      ps.setObject(4, OffsetDateTime.now());
      ps.executeUpdate();
    }
  }

  private void insertEntry(Connection c, UUID txId, UUID account, String direction, long amount)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount_minor)"
                + " VALUES (?, ?, ?, ?)")) {
      ps.setObject(1, txId);
      ps.setObject(2, account);
      ps.setString(3, direction);
      ps.setLong(4, amount);
      ps.executeUpdate();
    }
  }

  /** Runs {@code body} in one transaction and commits; returns the SQLException if any. */
  private Failure commitOf(SqlBody body) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        body.run(c);
        c.commit();
        return Failure.NONE;
      } catch (SQLException e) {
        c.rollback();
        return new Failure(e.getSQLState(), e.getMessage());
      }
    }
  }

  private record Failure(String sqlState, String message) {
    static final Failure NONE = new Failure(null, null);
  }

  @FunctionalInterface
  private interface SqlBody {
    void run(Connection c) throws SQLException;
  }

  private long entryCount(UUID txId) {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", Long.class, txId);
    return n == null ? 0 : n;
  }

  @Test
  void aBalancedTransactionCommits() throws SQLException {
    Fixture f = fixture();
    UUID tx = UUID.randomUUID();
    Failure failure =
        commitOf(
            c -> {
              insertTransaction(c, f, tx);
              insertEntry(c, tx, f.debitAccount(), "DEBIT", 1000);
              insertEntry(c, tx, f.creditAccount(), "CREDIT", 1000);
            });
    assertThat(failure.sqlState()).isNull();
    assertThat(entryCount(tx)).isEqualTo(2);
  }

  @Test
  void anUnbalancedTransactionIsRejectedAtCommit() throws SQLException {
    Fixture f = fixture();
    UUID tx = UUID.randomUUID();
    Failure failure =
        commitOf(
            c -> {
              insertTransaction(c, f, tx);
              insertEntry(c, tx, f.debitAccount(), "DEBIT", 1000);
              insertEntry(c, tx, f.creditAccount(), "CREDIT", 999);
            });
    assertThat(failure.sqlState()).isNotNull();
    assertThat(failure.sqlState()).isEqualTo("23514");
    assertThat(failure.message()).contains("not balanced");
    assertThat(entryCount(tx)).isZero();
  }

  @Test
  void aSingleSidedTransactionIsRejected() throws SQLException {
    Fixture f = fixture();
    UUID tx = UUID.randomUUID();
    Failure failure =
        commitOf(
            c -> {
              insertTransaction(c, f, tx);
              insertEntry(c, tx, f.debitAccount(), "DEBIT", 1000);
            });
    assertThat(failure.sqlState()).isNotNull();
    assertThat(failure.sqlState()).isEqualTo("23514");
  }

  @Test
  void aTransactionHeaderWithoutEntriesIsRejected() throws SQLException {
    Fixture f = fixture();
    UUID tx = UUID.randomUUID();
    Failure failure = commitOf(c -> insertTransaction(c, f, tx));
    assertThat(failure.sqlState()).isNotNull();
    assertThat(failure.sqlState()).isEqualTo("23514");
  }

  @Test
  void appendingAnEntryToACommittedTransactionBreaksBalanceAndIsRejected() throws SQLException {
    Fixture f = fixture();
    UUID tx = UUID.randomUUID();
    commitOf(
        c -> {
          insertTransaction(c, f, tx);
          insertEntry(c, tx, f.debitAccount(), "DEBIT", 1000);
          insertEntry(c, tx, f.creditAccount(), "CREDIT", 1000);
        });
    Failure failure = commitOf(c -> insertEntry(c, tx, f.debitAccount(), "DEBIT", 1));
    assertThat(failure.sqlState()).isNotNull();
    assertThat(failure.sqlState()).isEqualTo("23514");
    assertThat(entryCount(tx)).isEqualTo(2);
  }

  @Test
  void entriesCannotBeNonPositive() throws SQLException {
    Fixture f = fixture();
    UUID tx = UUID.randomUUID();
    Failure zero =
        commitOf(
            c -> {
              insertTransaction(c, f, tx);
              insertEntry(c, tx, f.debitAccount(), "DEBIT", 0);
            });
    assertThat(zero.sqlState()).isNotNull();
    assertThat(zero.sqlState()).isEqualTo("23514");
    Failure negative =
        commitOf(
            c -> {
              insertTransaction(c, f, UUID.randomUUID());
              insertEntry(c, tx, f.debitAccount(), "DEBIT", -5);
            });
    assertThat(negative.sqlState()).isNotNull();
  }

  @Test
  void ledgerEntriesAreAppendOnly() throws SQLException {
    Fixture f = fixture();
    UUID tx = UUID.randomUUID();
    commitOf(
        c -> {
          insertTransaction(c, f, tx);
          insertEntry(c, tx, f.debitAccount(), "DEBIT", 1000);
          insertEntry(c, tx, f.creditAccount(), "CREDIT", 1000);
        });

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE ledger_entries SET amount_minor = 1 WHERE transaction_id = ?", tx))
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE transaction_id = ?", tx))
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () -> jdbc.update("UPDATE ledger_transactions SET description = 'x' WHERE id = ?", tx))
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_transactions WHERE id = ?", tx))
        .hasMessageContaining("append-only");
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      assertThatThrownBy(() -> st.execute("TRUNCATE ledger_entries"))
          .hasMessageContaining("append-only");
    }
    assertThat(entryCount(tx)).isEqualTo(2);
  }

  @Test
  void theDatabaseRefusesCountersThatBreakTheirBounds() {
    UUID id = UUID.randomUUID();
    String merchant = newMerchant();
    String insert =
        """
        INSERT INTO payments (id, merchant_id, amount_minor, currency, authorized_minor,
          captured_minor, refunded_minor, voided_minor, state, psp_reference, created_at, updated_at)
        VALUES (?, ?, 100, 'USD', 100, ?, ?, ?, 'AUTHORIZED', 'p', now(), now())
        """;
    assertThatThrownBy(() -> jdbc.update(insert, id, merchant, 101L, 0L, 0L))
        .hasMessageContaining("payments_captured_plus_voided_bounded");
    assertThatThrownBy(() -> jdbc.update(insert, id, merchant, 60L, 0L, 41L))
        .hasMessageContaining("payments_captured_plus_voided_bounded");
    assertThatThrownBy(() -> jdbc.update(insert, id, merchant, 10L, 11L, 0L))
        .hasMessageContaining("payments_refunded_bounded");
    assertThatThrownBy(() -> jdbc.update(insert, id, merchant, Long.MAX_VALUE, 0L, Long.MAX_VALUE))
        .hasMessageContaining("payments_captured_plus_voided_bounded");
  }
}
