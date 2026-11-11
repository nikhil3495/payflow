package com.payflow.infrastructure;

import com.payflow.domain.Payment;
import com.payflow.domain.PaymentState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

  private static final String COLUMNS =
      """
      id, merchant_id, amount_minor, currency, authorized_minor, captured_minor, refunded_minor,
      voided_minor, state, psp_reference, reference, version, created_at, updated_at
      """;

  private static final RowMapper<Payment> MAPPER = PaymentRepository::map;

  private final JdbcClient jdbc;

  public PaymentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Payment p) {
    jdbc.sql(
            """
            INSERT INTO payments (id, merchant_id, amount_minor, currency, authorized_minor,
              captured_minor, refunded_minor, voided_minor, state, psp_reference, reference,
              version, created_at, updated_at)
            VALUES (:id, :merchant, :amount, :currency, :authorized, :captured, :refunded, :voided,
              :state, :psp, :reference, :version, :createdAt, :updatedAt)
            """)
        .param("id", p.id())
        .param("merchant", p.merchantId())
        .param("amount", p.amountMinor())
        .param("currency", p.currency())
        .param("authorized", p.authorizedMinor())
        .param("captured", p.capturedMinor())
        .param("refunded", p.refundedMinor())
        .param("voided", p.voidedMinor())
        .param("state", p.state().name())
        .param("psp", p.pspReference())
        .param("reference", p.reference())
        .param("version", p.version())
        .param("createdAt", Db.ts(p.createdAt()))
        .param("updatedAt", Db.ts(p.updatedAt()))
        .update();
  }

  public Optional<Payment> find(UUID id, String merchantId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM payments WHERE id = :id AND merchant_id = :merchant")
        .param("id", id)
        .param("merchant", merchantId)
        .query(MAPPER)
        .optional();
  }

  /**
   * Takes the row lock that serializes every money-moving operation on this payment. Callers must
   * read balances only from the returned row.
   */
  public Optional<Payment> lockForUpdate(UUID id, String merchantId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM payments WHERE id = :id AND merchant_id = :merchant FOR UPDATE")
        .param("id", id)
        .param("merchant", merchantId)
        .query(MAPPER)
        .optional();
  }

  /** Optimistic guard on top of the row lock: a stale write can never silently succeed. */
  public Payment update(Payment p) {
    int rows =
        jdbc.sql(
                """
                UPDATE payments
                   SET captured_minor = :captured, refunded_minor = :refunded,
                       voided_minor = :voided, state = :state, updated_at = :updatedAt,
                       version = version + 1
                 WHERE id = :id AND version = :version
                """)
            .param("captured", p.capturedMinor())
            .param("refunded", p.refundedMinor())
            .param("voided", p.voidedMinor())
            .param("state", p.state().name())
            .param("updatedAt", Db.ts(p.updatedAt()))
            .param("id", p.id())
            .param("version", p.version())
            .update();
    if (rows != 1) {
      throw new IllegalStateException("lost update on payment " + p.id());
    }
    return p.withVersion(p.version() + 1);
  }

  /** Keyset page of payments for reconciliation, ordered by id. */
  public List<Payment> pageAfter(UUID afterId, int limit) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM payments WHERE id > :after ORDER BY id LIMIT :limit")
        .param("after", afterId)
        .param("limit", limit)
        .query(MAPPER)
        .list();
  }

  private static Payment map(ResultSet rs, int rowNum) throws SQLException {
    return new Payment(
        Db.uuid(rs, "id"),
        rs.getString("merchant_id"),
        rs.getLong("amount_minor"),
        rs.getString("currency"),
        rs.getLong("authorized_minor"),
        rs.getLong("captured_minor"),
        rs.getLong("refunded_minor"),
        rs.getLong("voided_minor"),
        PaymentState.valueOf(rs.getString("state")),
        rs.getString("psp_reference"),
        rs.getString("reference"),
        rs.getLong("version"),
        Db.instant(rs, "created_at"),
        Db.instant(rs, "updated_at"));
  }
}
