package com.payflow.support;

import com.payflow.config.PayflowProperties;
import java.time.Duration;
import java.util.Set;

/** Builds {@link PayflowProperties} for unit tests that do not start Spring. */
public final class TestProperties {

  private TestProperties() {}

  public static PayflowProperties defaults() {
    return build(false, false, 8);
  }

  public static PayflowProperties build(
      boolean allowPrivateTargets, boolean allowInsecureTargets, int maxRetryAttempts) {
    return new PayflowProperties(
        250,
        Set.of("USD", "EUR"),
        65_536,
        "unit-test-admin-token-0123456789",
        new PayflowProperties.Idempotency(Duration.ofHours(24)),
        new PayflowProperties.Retry(
            maxRetryAttempts, Duration.ofMillis(10), Duration.ofMillis(500)),
        new PayflowProperties.Webhook(
            "unit-test-psp-secret-0123456789ab",
            Duration.ofMinutes(5),
            8,
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            25,
            allowPrivateTargets,
            allowInsecureTargets),
        new PayflowProperties.Scheduling(
            false, Duration.ofSeconds(2), Duration.ofHours(1), "0 0 2 * * *"));
  }
}
