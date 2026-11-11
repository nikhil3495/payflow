package com.payflow.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationRepository {

  public static final String RUNNING = "RUNNING";
  public static final String COMPLETED = "COMPLETED";
  public static final String FAILED = "FAILED";

  public record Run(
      UUID id,
      String state,
      Instant startedAt,
      Instant finishedAt,
      long paymentsChecked,
      long discrepancyCount,
      String failureReason) {}

  public record Discrepancy(
      UUID id,
      UUID runId,
      UUID paymentId,
      String kind,
      String subject,
      long expectedMinor,
      long actualMinor,
      Instant detectedAt) {}

  private final JdbcClient jdbc;

  public ReconciliationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Advisory lock held until the end of the transaction; false means another run is active. */
  public boolean tryLockRun() {
    Boolean acquired =
        jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
            .param("key", 7_201_001L)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(acquired);
  }

  public void insertRun(UUID id, Instant now) {
    jdbc.sql(
            "INSERT INTO reconciliation_runs (id, state, started_at) VALUES (:id, 'RUNNING', :now)")
        .param("id", id)
        .param("now", Db.ts(now))
        .update();
  }

  public void finishRun(
      UUID id, String state, Instant now, long checked, long discrepancies, String failureReason) {
    jdbc.sql(
            """
            UPDATE reconciliation_runs
               SET state = :state, finished_at = :now, payments_checked = :checked,
                   discrepancy_count = :discrepancies, failure_reason = :reason
             WHERE id = :id
            """)
        .param("state", state)
        .param("now", Db.ts(now))
        .param("checked", checked)
        .param("discrepancies", discrepancies)
        .param("reason", failureReason)
        .param("id", id)
        .update();
  }

  public void insertDiscrepancy(Discrepancy d) {
    jdbc.sql(
            """
            INSERT INTO reconciliation_discrepancies (id, run_id, payment_id, kind, subject,
              expected_minor, actual_minor, detected_at)
            VALUES (:id, :run, :payment, :kind, :subject, :expected, :actual, :at)
            """)
        .param("id", d.id())
        .param("run", d.runId())
        .param("payment", d.paymentId())
        .param("kind", d.kind())
        .param("subject", d.subject())
        .param("expected", d.expectedMinor())
        .param("actual", d.actualMinor())
        .param("at", Db.ts(d.detectedAt()))
        .update();
  }

  public Optional<Run> findRun(UUID id) {
    return jdbc.sql(RUN_SELECT + " WHERE id = :id").param("id", id).query(RUN_MAPPER).optional();
  }

  public List<Run> latestRuns(int limit) {
    return jdbc.sql(RUN_SELECT + " ORDER BY started_at DESC, id DESC LIMIT :limit")
        .param("limit", limit)
        .query(RUN_MAPPER)
        .list();
  }

  public List<Discrepancy> discrepancies(UUID runId) {
    return jdbc.sql(
            """
            SELECT id, run_id, payment_id, kind, subject, expected_minor, actual_minor, detected_at
              FROM reconciliation_discrepancies
             WHERE run_id = :run
             ORDER BY payment_id, kind, subject
            """)
        .param("run", runId)
        .query(
            (rs, n) ->
                new Discrepancy(
                    Db.uuid(rs, "id"),
                    Db.uuid(rs, "run_id"),
                    Db.uuid(rs, "payment_id"),
                    rs.getString("kind"),
                    rs.getString("subject"),
                    rs.getLong("expected_minor"),
                    rs.getLong("actual_minor"),
                    Db.instant(rs, "detected_at")))
        .list();
  }

  private static final String RUN_SELECT =
      "SELECT id, state, started_at, finished_at, payments_checked, discrepancy_count, failure_reason"
          + " FROM reconciliation_runs";

  private static final org.springframework.jdbc.core.RowMapper<Run> RUN_MAPPER =
      (rs, n) ->
          new Run(
              Db.uuid(rs, "id"),
              rs.getString("state"),
              Db.instant(rs, "started_at"),
              Db.instant(rs, "finished_at"),
              rs.getLong("payments_checked"),
              rs.getLong("discrepancy_count"),
              rs.getString("failure_reason"));
}
