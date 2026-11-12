package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.application.ReconciliationService;
import com.payflow.infrastructure.ReconciliationRepository.Run;
import com.payflow.support.IntegrationTest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What an operator's alerts can see: a run that did not happen must not look like a clean run. */
class ReconciliationMonitoringTest extends IntegrationTest {

  private static final long RUN_LOCK_KEY = 7_201_001L;
  private static final String DISCREPANCIES = "payflow.reconciliation.last.discrepancies";
  private static final String LAST_SUCCESS =
      "payflow.reconciliation.last.success.timestamp.seconds";

  @Autowired ReconciliationService reconciliation;
  @Autowired MeterRegistry meters;
  @Autowired DataSource dataSource;

  private double gauge(String name) {
    Gauge gauge = meters.find(name).gauge();
    assertThat(gauge).as("gauge %s is registered", name).isNotNull();
    return gauge.value();
  }

  /** Runs reconciliation while another session holds the run lock, i.e. it is skipped. */
  private Run runWhileAnotherRunIsActive() throws SQLException {
    try (Connection other = dataSource.getConnection();
        Statement st = other.createStatement()) {
      st.execute("SELECT pg_advisory_lock(" + RUN_LOCK_KEY + ")");
      try {
        return reconciliation.run();
      } finally {
        st.execute("SELECT pg_advisory_unlock(" + RUN_LOCK_KEY + ")");
      }
    }
  }

  @Test
  void aSkippedRunDoesNotReportAllClearOrLookLikeARecentSuccess() throws SQLException {
    String merchant = newMerchant();
    String id = api().authorizeOk(merchant, 10_000, "USD");
    api().capture(merchant, newKey(), id, 6_000, "USD");
    jdbc.update("UPDATE payments SET captured_minor = 1 WHERE id = ?::uuid", id);

    Run real = reconciliation.run();
    assertThat(real.state()).isEqualTo("COMPLETED");
    assertThat(real.discrepancyCount()).isPositive();
    assertThat(gauge(DISCREPANCIES)).isEqualTo(real.discrepancyCount());
    assertThat(gauge(LAST_SUCCESS)).isEqualTo(real.finishedAt().getEpochSecond());
    double skippedBefore =
        meters.counter("payflow.reconciliation.runs", "state", "skipped").count();

    Run skipped = runWhileAnotherRunIsActive();

    assertThat(skipped.state()).isEqualTo("FAILED");
    // The last real answer still stands: not zeroed, and its age is still visible.
    assertThat(gauge(DISCREPANCIES)).isEqualTo(real.discrepancyCount());
    assertThat(gauge(LAST_SUCCESS)).isEqualTo(real.finishedAt().getEpochSecond());
    // The skip is countable on its own, not folded into clean runs.
    assertThat(
            meters.counter("payflow.reconciliation.runs", "state", "skipped").count()
                - skippedBefore)
        .isEqualTo(1);
  }
}
