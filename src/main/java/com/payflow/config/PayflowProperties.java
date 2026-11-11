package com.payflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** All tunables, bound from {@code payflow.*} (environment variables map via relaxed binding). */
@Validated
@ConfigurationProperties(prefix = "payflow")
public record PayflowProperties(
    @Min(0) @Max(10_000) @DefaultValue("250") int feeBps,
    @NotEmpty @DefaultValue({"USD", "EUR", "GBP", "JPY", "INR", "AUD", "CAD", "CHF", "SGD"})
        Set<String> supportedCurrencies,
    @Min(1024) @DefaultValue("65536") int maxBodyBytes,
    @NotBlank @Size(min = 24) String adminToken,
    @Valid @NotNull @DefaultValue Idempotency idempotency,
    @Valid @NotNull @DefaultValue Retry retry,
    @Valid @NotNull Webhook webhook,
    @Valid @NotNull @DefaultValue Scheduling scheduling) {

  /**
   * Keys are kept 24h: long enough to cover client retry windows, short enough to bound storage.
   */
  public record Idempotency(@NotNull @DefaultValue("24h") Duration ttl) {}

  public record Retry(
      @Min(1) @Max(50) @DefaultValue("8") int maxAttempts,
      @NotNull @DefaultValue("10ms") Duration baseBackoff,
      @NotNull @DefaultValue("500ms") Duration maxBackoff) {}

  public record Webhook(
      @NotBlank @Size(min = 24) String pspSecret,
      @NotNull @DefaultValue("5m") Duration signatureTolerance,
      @Min(1) @Max(50) @DefaultValue("8") int maxDeliveryAttempts,
      @NotNull @DefaultValue("30s") Duration deliveryBaseDelay,
      @NotNull @DefaultValue("1h") Duration deliveryMaxDelay,
      @NotNull @DefaultValue("5s") Duration deliveryTimeout,
      @NotNull @DefaultValue("2m") Duration deliveryLease,
      @Min(1) @Max(500) @DefaultValue("25") int batchSize,
      @DefaultValue("false") boolean allowPrivateTargets,
      @DefaultValue("false") boolean allowInsecureTargets) {}

  public record Scheduling(
      @DefaultValue("true") boolean enabled,
      @NotNull @DefaultValue("2s") Duration webhookPollInterval,
      @NotNull @DefaultValue("1h") Duration idempotencyPurgeInterval,
      @NotBlank @DefaultValue("0 0 2 * * *") String reconciliationCron) {}
}
