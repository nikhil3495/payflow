package com.payflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.config.PayflowProperties;
import com.payflow.infrastructure.OperationRepository;
import com.payflow.infrastructure.WebhookRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles webhooks pushed by the PSP: authenticity first (HMAC over the raw bytes), then
 * exactly-once effect via a unique constraint on the PSP's event id.
 */
@Service
public class InboundWebhookService {

  public enum Result {
    PROCESSED,
    DUPLICATE,
    IGNORED,
    UNMATCHED
  }

  public static final String OPERATION_CONFIRMED = "operation.confirmed";
  public static final String OPERATION_FAILED = "operation.failed";

  private static final Logger LOG = LoggerFactory.getLogger(InboundWebhookService.class);
  private static final int MAX_EVENT_ID = 255;
  private static final int MAX_EVENT_TYPE = 128;

  private final byte[] secret;
  private final PayflowProperties.Webhook settings;
  private final ObjectMapper mapper;
  private final WebhookRepository webhooks;
  private final OperationRepository operations;
  private final Clock clock;
  private final IdGenerator ids;
  private final TransactionTemplate tx;

  public InboundWebhookService(
      PayflowProperties properties,
      ObjectMapper mapper,
      WebhookRepository webhooks,
      OperationRepository operations,
      Clock clock,
      IdGenerator ids,
      PlatformTransactionManager txManager) {
    this.settings = properties.webhook();
    this.secret = settings.pspSecret().getBytes(StandardCharsets.UTF_8);
    this.mapper = mapper;
    this.webhooks = webhooks;
    this.operations = operations;
    this.clock = clock;
    this.ids = ids;
    this.tx = new TransactionTemplate(txManager);
  }

  /**
   * @param rawBody the exact bytes received; the signature covers these, not any parsed form
   */
  public Result handle(byte[] rawBody, String signatureHeader) {
    try {
      WebhookSignature.verify(
          secret, signatureHeader, rawBody, clock.instant(), settings.signatureTolerance());
    } catch (WebhookSignature.InvalidSignatureException e) {
      LOG.warn("rejected inbound webhook: {}", e.failure());
      throw new ApiException(
          ErrorCode.INVALID_WEBHOOK_SIGNATURE, "The webhook signature is invalid.");
    }

    JsonNode event = parse(rawBody);
    String eventId = text(event, "id", MAX_EVENT_ID);
    String type = text(event, "type", MAX_EVENT_TYPE);
    String reference = event.path("data").path("psp_reference").asText("");

    Result result =
        tx.execute(
            status -> {
              UUID rowId = ids.next();
              boolean first =
                  webhooks.insertEventIfAbsent(
                      rowId,
                      eventId,
                      type,
                      new String(rawBody, StandardCharsets.UTF_8),
                      clock.instant());
              if (!first) {
                // The PSP is called inside our payment transaction, so its event can arrive before
                // that transaction commits and match nothing. Such an event never took effect, so
                // a redelivery must be applied; only events that did take effect are duplicates.
                Optional<UUID> unmatched = webhooks.lockUnmatchedEvent(eventId);
                if (unmatched.isEmpty()) {
                  return Result.DUPLICATE;
                }
                rowId = unmatched.get();
              }
              Result outcome = apply(type, reference);
              webhooks.setEventState(
                  rowId, outcome == Result.PROCESSED ? "PROCESSED" : outcome.name());
              return outcome;
            });
    LOG.info("inbound webhook {} ({}) -> {}", eventId, type, result);
    return result;
  }

  public enum ReplayResult {
    REPLAYED,
    ALREADY_PROCESSED
  }

  /**
   * Operator replay of an event the sweeper abandoned. Runs under the event row's lock, so it is
   * safe to call twice or concurrently: the first call applies it, later ones see PROCESSED and do
   * nothing. It never applies an event whose operation still does not exist (that is a 409 and
   * nothing changes), and never touches an event that is not ABANDONED.
   */
  public ReplayResult replayAbandoned(String pspEventId) {
    ReplayResult result = tx.execute(status -> replayInTransaction(pspEventId));
    if (result == null) {
      throw new IllegalStateException("replay produced no result");
    }
    LOG.warn("operator replay of inbound webhook {} -> {}", pspEventId, result);
    return result;
  }

  private ReplayResult replayInTransaction(String pspEventId) {
    WebhookRepository.EventRow row =
        webhooks
            .lockEvent(pspEventId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such inbound event."));
    if ("PROCESSED".equals(row.state())) {
      return ReplayResult.ALREADY_PROCESSED;
    }
    if (!"ABANDONED".equals(row.state())) {
      throw new ApiException(
          ErrorCode.EVENT_NOT_REPLAYABLE,
          "Only ABANDONED events can be replayed; this one is " + row.state() + ".");
    }
    if (apply(row.type(), referenceOf(row.payload())) != Result.PROCESSED) {
      throw new ApiException(
          ErrorCode.EVENT_NOT_REPLAYABLE,
          "The operation this event refers to still does not exist.");
    }
    webhooks.markReplayed(row.id(), clock.instant());
    return ReplayResult.REPLAYED;
  }

  /** The operation reference named by a stored event payload; empty if it has none. */
  String referenceOf(String payload) {
    try {
      return mapper.readTree(payload).path("data").path("psp_reference").asText("");
    } catch (IOException unreadable) {
      return "";
    }
  }

  Result apply(String type, String reference) {
    String pspStatus =
        switch (type) {
          case OPERATION_CONFIRMED -> "CONFIRMED";
          case OPERATION_FAILED -> "FAILED";
          default -> null;
        };
    if (pspStatus == null) {
      return Result.IGNORED;
    }
    return operations.updatePspStatus(reference, pspStatus) > 0
        ? Result.PROCESSED
        : Result.UNMATCHED;
  }

  private JsonNode parse(byte[] rawBody) {
    try {
      JsonNode node = mapper.readTree(rawBody);
      if (node == null || !node.isObject()) {
        throw new ApiException(
            ErrorCode.MALFORMED_REQUEST, "The webhook body must be a JSON object.");
      }
      return node;
    } catch (IOException e) {
      throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The webhook body is not valid JSON.");
    }
  }

  private static String text(JsonNode node, String field, int maxLength) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          "The webhook is missing a valid '" + field + "'.",
          java.util.List.of(new ApiException.FieldIssue(field, "must be a non-blank string")),
          null);
    }
    return value.asText();
  }
}
