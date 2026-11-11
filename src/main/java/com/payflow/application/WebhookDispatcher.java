package com.payflow.application;

import com.payflow.config.PayflowProperties;
import com.payflow.infrastructure.WebhookRepository;
import com.payflow.infrastructure.WebhookRepository.Delivery;
import com.payflow.infrastructure.WebhookRepository.Endpoint;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers outbound webhooks at-least-once. Failed deliveries are retried with exponential backoff
 * and full jitter; after the configured number of attempts they land in the dead-letter table.
 *
 * <p>Receivers must treat deliveries idempotently: the same event id can arrive more than once (a
 * retry after a timeout whose request actually succeeded, or a lease that expired while a slow
 * delivery was still in flight). Every delivery carries a stable {@code PayFlow-Event-Id}.
 */
@Component
public class WebhookDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookDispatcher.class);
  private static final int MAX_ERROR_LENGTH = 500;

  private final WebhookRepository webhooks;
  private final WebhookTargetValidator validator;
  private final PayflowProperties.Webhook settings;
  private final BackoffPolicy backoff;
  private final Clock clock;
  private final RandomGenerator random;
  private final MeterRegistry meters;
  private final TransactionTemplate tx;
  private final HttpClient client;

  public WebhookDispatcher(
      WebhookRepository webhooks,
      WebhookTargetValidator validator,
      PayflowProperties properties,
      Clock clock,
      RandomGenerator random,
      MeterRegistry meters,
      PlatformTransactionManager txManager) {
    this.webhooks = webhooks;
    this.validator = validator;
    this.settings = properties.webhook();
    this.backoff = new BackoffPolicy(settings.deliveryBaseDelay(), settings.deliveryMaxDelay());
    this.clock = clock;
    this.random = random;
    this.meters = meters;
    this.tx = new TransactionTemplate(txManager);
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** Attempts every due delivery once; returns how many were attempted. */
  public int processDue() {
    Instant now = clock.instant();
    List<Delivery> claimed =
        tx.execute(
            status ->
                webhooks.claimDue(now, settings.batchSize(), now.plus(settings.deliveryLease())));
    if (claimed == null || claimed.isEmpty()) {
      return 0;
    }
    for (Delivery delivery : claimed) {
      deliver(delivery);
    }
    return claimed.size();
  }

  private void deliver(Delivery delivery) {
    int attempt = delivery.attemptCount() + 1;
    Integer status = null;
    String error = null;
    boolean delivered = false;
    try {
      Endpoint endpoint =
          webhooks
              .findEndpoint(delivery.merchantId())
              .orElseThrow(() -> new IllegalStateException("no webhook endpoint registered"));
      URI uri = validator.validate(endpoint.url());
      byte[] body = delivery.payload().getBytes(StandardCharsets.UTF_8);
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(settings.deliveryTimeout())
              .header("Content-Type", "application/json")
              .header(
                  "PayFlow-Signature",
                  WebhookSignature.header(
                      endpoint.signingSecret().getBytes(StandardCharsets.UTF_8),
                      clock.instant(),
                      body))
              .header("PayFlow-Event-Id", delivery.id().toString())
              .header("PayFlow-Delivery-Attempt", Integer.toString(attempt))
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
              .build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      status = response.statusCode();
      delivered = status >= 200 && status < 300;
      if (!delivered) {
        error = "HTTP " + status;
      }
    } catch (InterruptedException e) {
      // Shutting down: leave the lease to expire so another dispatcher retries it.
      Thread.currentThread().interrupt();
      return;
    } catch (IOException | RuntimeException e) {
      error = abbreviate(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    record(delivery, attempt, delivered, status, error);
  }

  private void record(
      Delivery delivery, int attempt, boolean delivered, Integer status, String error) {
    Instant now = clock.instant();
    String outcome;
    if (delivered) {
      tx.executeWithoutResult(s -> webhooks.markDelivered(delivery.id(), attempt, status, now));
      outcome = "delivered";
    } else if (attempt >= settings.maxDeliveryAttempts()) {
      tx.executeWithoutResult(
          s ->
              webhooks.markDead(
                  delivery.id(),
                  delivery.merchantId(),
                  delivery.eventType(),
                  delivery.payload(),
                  attempt,
                  status,
                  error,
                  now));
      outcome = "dead";
      LOG.error("webhook delivery {} exhausted {} attempts: {}", delivery.id(), attempt, error);
    } else {
      Instant next = now.plus(backoff.delayFor(attempt, random));
      tx.executeWithoutResult(s -> webhooks.markRetry(delivery.id(), attempt, next, status, error));
      outcome = "retry";
      LOG.warn(
          "webhook delivery {} attempt {} failed ({}); retrying at {}",
          delivery.id(),
          attempt,
          error,
          next);
    }
    meters.counter("payflow.webhook.deliveries", "outcome", outcome).increment();
  }

  private static String abbreviate(String text) {
    return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
  }
}
