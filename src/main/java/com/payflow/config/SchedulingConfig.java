package com.payflow.config;

import com.payflow.application.IdempotencyPurgeJob;
import com.payflow.application.WebhookDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Registers the background jobs. Disabled in tests via {@code payflow.scheduling.enabled=false} so
 * they never race with assertions; tests invoke the jobs directly instead.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "payflow.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SchedulingConfig implements SchedulingConfigurer {

  private final PayflowProperties properties;
  private final IdempotencyPurgeJob purgeJob;
  private final WebhookDispatcher dispatcher;

  public SchedulingConfig(
      PayflowProperties properties, IdempotencyPurgeJob purgeJob, WebhookDispatcher dispatcher) {
    this.dispatcher = dispatcher;
    this.properties = properties;
    this.purgeJob = purgeJob;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedDelayTask(
        purgeJob::purgeExpired, properties.scheduling().idempotencyPurgeInterval());
    registrar.addFixedDelayTask(
        dispatcher::processDue, properties.scheduling().webhookPollInterval());
  }
}
