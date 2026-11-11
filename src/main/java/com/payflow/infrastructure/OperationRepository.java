package com.payflow.infrastructure;

import com.payflow.domain.OperationType;
import com.payflow.domain.PaymentOperation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OperationRepository {

  private static final String COLUMNS =
      "id, payment_id, type, amount_minor, fee_minor, state, psp_reference, psp_status, created_at";
  private static final RowMapper<PaymentOperation> MAPPER = OperationRepository::map;

  private final JdbcClient jdbc;

  public OperationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(PaymentOperation op) {
    jdbc.sql(
            """
            INSERT INTO payment_operations (id, payment_id, type, amount_minor, fee_minor, state,
              psp_reference, psp_status, created_at)
            VALUES (:id, :payment, :type, :amount, :fee, :state, :psp, :pspStatus, :createdAt)
            """)
        .param("id", op.id())
        .param("payment", op.paymentId())
        .param("type", op.type().name())
        .param("amount", op.amountMinor())
        .param("fee", op.feeMinor())
        .param("state", op.state())
        .param("psp", op.pspReference())
        .param("pspStatus", op.pspStatus())
        .param("createdAt", Db.ts(op.createdAt()))
        .update();
  }

  /** One query for many payments, so reconciliation never issues a query per payment. */
  public List<PaymentOperation> findByPayments(Collection<UUID> paymentIds) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM payment_operations WHERE payment_id = ANY(:ids) ORDER BY created_at, id")
        .param("ids", paymentIds.toArray(UUID[]::new))
        .query(MAPPER)
        .list();
  }

  public List<PaymentOperation> findByPayment(UUID paymentId) {
    return findByPayments(List.of(paymentId));
  }

  public int updatePspStatus(String pspReference, String pspStatus) {
    return jdbc.sql("UPDATE payment_operations SET psp_status = :status WHERE psp_reference = :ref")
        .param("status", pspStatus)
        .param("ref", pspReference)
        .update();
  }

  private static PaymentOperation map(ResultSet rs, int rowNum) throws SQLException {
    return new PaymentOperation(
        Db.uuid(rs, "id"),
        Db.uuid(rs, "payment_id"),
        OperationType.valueOf(rs.getString("type")),
        rs.getLong("amount_minor"),
        rs.getLong("fee_minor"),
        rs.getString("state"),
        rs.getString("psp_reference"),
        rs.getString("psp_status"),
        Db.instant(rs, "created_at"));
  }
}
