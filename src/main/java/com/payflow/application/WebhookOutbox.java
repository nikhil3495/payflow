package com.payflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.domain.Payment;
import com.payflow.infrastructure.WebhookRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional outbox: the delivery row is written in the same transaction as the payment change,
 * so an event exists if and only if the change committed. A separate dispatcher sends it.
 */
@Component
public class WebhookOutbox {

  public static final String AUTHORIZED = "payment.authorized";
  public static final String CAPTURED = "payment.captured";
  public static final String REFUNDED = "payment.refunded";
  public static final String VOIDED = "payment.voided";

  /** Payload sent to merchants. */
  public record Event(
      UUID id, String type, Instant createdAt, UUID operationId, PaymentData data) {}

  public record PaymentData(
      UUID id,
      String state,
      long amountMinor,
      String currency,
      long authorizedMinor,
      long capturedMinor,
      long refundedMinor,
      long voidedMinor,
      String reference) {}

  private final WebhookRepository webhooks;
  private final ObjectMapper mapper;
  private final IdGenerator ids;
  private final Clock clock;

  public WebhookOutbox(
      WebhookRepository webhooks, ObjectMapper mapper, IdGenerator ids, Clock clock) {
    this.webhooks = webhooks;
    this.mapper = mapper;
    this.ids = ids;
    this.clock = clock;
  }

  /** Must run inside the transaction that changed the payment. */
  public void enqueue(String eventType, Payment payment, UUID operationId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "webhook events must be enqueued inside the payment transaction");
    }
    if (webhooks.findEndpoint(payment.merchantId()).isEmpty()) {
      return;
    }
    Instant now = clock.instant();
    UUID eventId = ids.next();
    Event event =
        new Event(
            eventId,
            eventType,
            now,
            operationId,
            new PaymentData(
                payment.id(),
                payment.state().name(),
                payment.amountMinor(),
                payment.currency(),
                payment.authorizedMinor(),
                payment.capturedMinor(),
                payment.refundedMinor(),
                payment.voidedMinor(),
                payment.reference()));
    try {
      webhooks.insertDelivery(
          eventId,
          payment.merchantId(),
          payment.id(),
          eventType,
          mapper.writeValueAsString(event),
          now);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("webhook event could not be serialized", e);
    }
  }
}
