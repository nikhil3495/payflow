package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.application.PaymentService;
import com.payflow.application.PaymentService.MoneyCommand;
import com.payflow.support.Api;
import com.payflow.support.Api.Response;
import com.payflow.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

class OperationalTest extends IntegrationTest {

  @Autowired MeterRegistry meters;
  @Autowired DataSource dataSource;
  @Autowired Environment environment;
  @Autowired PaymentService paymentService;

  @Test
  void healthAndProbesAreUp() {
    Api api = api();
    assertThat(api.get("/actuator/health", null).json().path("status").asText()).isEqualTo("UP");
    assertThat(api.get("/actuator/health/liveness", null).json().path("status").asText())
        .isEqualTo("UP");
    Response readiness = api.get("/actuator/health/readiness", null);
    assertThat(readiness.status()).isEqualTo(200);
    assertThat(readiness.json().path("status").asText()).isEqualTo("UP");
    // Details are not leaked to anonymous callers.
    assertThat(api.get("/actuator/health", null).body())
        .doesNotContain("jdbc")
        .doesNotContain("postgres");
  }

  @Test
  void onlyTheIntendedActuatorEndpointsAreExposed() {
    Api api = api();
    assertThat(api.get("/actuator/prometheus", null).status()).isEqualTo(200);
    for (String hidden :
        new String[] {
          "/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers"
        }) {
      assertThat(api.get(hidden, null).status()).as(hidden).isEqualTo(404);
    }
  }

  @Test
  void paymentOperationsAreMeasured() {
    Api api = api();
    String merchant = newMerchant();
    String id = api.authorizeOk(merchant, 1_000, "USD");
    api.capture(merchant, "metrics-cap", id, 100, "USD");
    api.capture(merchant, "metrics-cap", id, 100, "USD"); // replay
    api.capture(merchant, newKey(), id, 5_000, "USD"); // client error

    String scrape = api.get("/actuator/prometheus", null).body();
    assertThat(scrape).contains("payflow_payment_operations_total{");
    assertThat(scrape).contains("operation=\"capture\"").contains("outcome=\"success\"");
    assertThat(scrape).contains("outcome=\"client_error\"").contains("replayed=\"true\"");
    assertThat(scrape).contains("payflow_payment_operation_duration_seconds_bucket");
    assertThat(
            meters
                .find("payflow.payment.operations")
                .tag("operation", "authorize")
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void theConnectionPoolIsSizedDeliberately() throws java.sql.SQLException {
    HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
    assertThat(hikari.getMaximumPoolSize()).isEqualTo(20);
    assertThat(hikari.getMinimumIdle()).isEqualTo(20);
    assertThat(hikari.getConnectionTimeout()).isEqualTo(30_000);
    assertThat(hikari.getPoolName()).isEqualTo("payflow-pool");
  }

  @Test
  void shutdownIsGraceful() {
    assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
        .isEqualTo("30s");
  }

  @Test
  void moneyMovingServiceMethodsRefuseToRunOutsideATransaction() {
    assertThatThrownBy(
            () ->
                paymentService.capture(
                    "m", "0198a000-0000-7000-8000-000000000000", new MoneyCommand(1, "USD")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transaction");
    assertThatThrownBy(
            () -> paymentService.voidPayment("m", "0198a000-0000-7000-8000-000000000000"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theOpenApiDocumentDescribesTheApi() {
    Response docs = api().get("/v3/api-docs", null);
    assertThat(docs.status()).isEqualTo(200);
    var paths = docs.json().path("paths");
    assertThat(paths.has("/v1/payments")).isTrue();
    assertThat(paths.has("/v1/payments/{id}/captures")).isTrue();
    assertThat(paths.has("/v1/payments/{id}/refunds")).isTrue();
    assertThat(paths.has("/v1/payments/{id}/voids")).isTrue();
    assertThat(paths.has("/v1/payments/{id}/ledger")).isTrue();
  }
}
