package com.payflow.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Micrometer instrumentation of payment operations. */
@Component
public class PaymentMetrics {

  private final MeterRegistry registry;

  public PaymentMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void record(String operation, int status, boolean replayed, Duration elapsed) {
    String outcome = status < 400 ? "success" : status < 500 ? "client_error" : "server_error";
    registry
        .counter(
            "payflow.payment.operations",
            "operation",
            operation,
            "outcome",
            outcome,
            "replayed",
            Boolean.toString(replayed))
        .increment();
    Timer.builder("payflow.payment.operation.duration")
        .tag("operation", operation)
        .publishPercentileHistogram()
        .register(registry)
        .record(elapsed);
  }
}
