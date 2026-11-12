package com.payflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.config.PayflowProperties;
import com.payflow.infrastructure.WebhookRepository;
import com.payflow.infrastructure.WebhookRepository.StoredEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies PSP events that arrived before the operation they describe was committed.
 *
 * <p>The PSP is called inside our payment transaction, so its event can reach us first. We answered
 * 200, so a well-behaved PSP will not re-send; this sweeper retries the stored event until its
 * operation exists. Events still unmatched after {@code unmatchedRetention} are marked ABANDONED
 * (kept, logged at ERROR, counted) and never retried, so a reference that never appears is not
 * resurrected months later.
 *
 * <p>Race-safety: candidates are locked with {@code FOR UPDATE SKIP LOCKED}, the same row lock a
 * redelivery takes in {@link InboundWebhookService}. Whoever holds it applies the event and flips
 * it to PROCESSED; the other sees it locked (sweeper) or no longer UNMATCHED (redelivery).
 */
@Component
public class UnmatchedEventSweepJob {

  public record Outcome(int applied, int abandoned) {}

  static final int BATCH_SIZE = 500;
  private static final Logger LOG = LoggerFactory.getLogger(UnmatchedEventSweepJob.class);

  private final WebhookRepository webhooks;
  private final InboundWebhookService inbound;
  private final ObjectMapper mapper;
  private final PayflowProperties.Webhook settings;
  private final Clock clock;
  private final MeterRegistry meters;
  private final TransactionTemplate tx;

  public UnmatchedEventSweepJob(
      WebhookRepository webhooks,
      InboundWebhookService inbound,
      ObjectMapper mapper,
      PayflowProperties properties,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager txManager) {
    this.webhooks = webhooks;
    this.inbound = inbound;
    this.mapper = mapper;
    this.settings = properties.webhook();
    this.clock = clock;
    this.meters = meters;
    this.tx = new TransactionTemplate(txManager);
  }

  public Outcome sweep() {
    Instant cutoff = clock.instant().minus(settings.unmatchedRetention());
    Integer applied = tx.execute(status -> applyPending(cutoff));
    List<StoredEvent> expired =
        java.util.Objects.requireNonNullElse(
            tx.execute(status -> webhooks.abandonUnmatchedUpTo(cutoff)), List.of());
    for (StoredEvent event : expired) {
      LOG.error(
          "inbound webhook {} ({}) matched no operation within {}; abandoned, payload kept in"
              + " webhook_events",
          event.pspEventId(),
          event.type(),
          settings.unmatchedRetention());
      meters.counter("payflow.webhook.inbound.abandoned").increment();
    }
    return new Outcome(applied == null ? 0 : applied, expired.size());
  }

  private int applyPending(Instant cutoff) {
    int applied = 0;
    for (StoredEvent event : webhooks.lockUnmatchedAfter(cutoff, BATCH_SIZE)) {
      String reference;
      try {
        JsonNode payload = mapper.readTree(event.payload());
        reference = payload.path("data").path("psp_reference").asText("");
      } catch (IOException unreadable) {
        LOG.warn("stored inbound webhook {} is unreadable; it will age out", event.pspEventId());
        continue;
      }
      if (inbound.apply(event.type(), reference) == InboundWebhookService.Result.PROCESSED) {
        webhooks.setEventState(event.id(), "PROCESSED");
        meters.counter("payflow.webhook.inbound.swept").increment();
        applied++;
      }
    }
    return applied;
  }
}
