package com.payflow.web;

import com.payflow.application.ApiException;
import com.payflow.application.ErrorCode;
import com.payflow.application.InboundWebhookService;
import com.payflow.infrastructure.WebhookRepository;
import com.payflow.infrastructure.WebhookRepository.EventRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator view of inbound PSP events, and the replay path for ones the sweeper abandoned (its
 * operation did not appear within the retention window). Requires the admin token. The token is
 * shared, so the audit trail is the event's {@code replayed_at} and a WARN log with the request's
 * correlation id, not an operator identity (see D29).
 */
@RestController
@RequestMapping("/v1/admin/inbound-events")
@Tag(name = "Operations")
public class InboundEventAdminController {

  private static final Set<String> STATES =
      Set.of("UNMATCHED", "ABANDONED", "PROCESSED", "IGNORED");

  public record EventResponse(
      String eventId, String type, String state, Instant receivedAt, Instant replayedAt) {
    static EventResponse from(EventRow row) {
      return new EventResponse(
          row.pspEventId(), row.type(), row.state(), row.receivedAt(), row.replayedAt());
    }
  }

  private final WebhookRepository webhooks;
  private final InboundWebhookService inbound;
  private final AdminAuthorizer admin;

  public InboundEventAdminController(
      WebhookRepository webhooks, InboundWebhookService inbound, AdminAuthorizer admin) {
    this.webhooks = webhooks;
    this.inbound = inbound;
    this.admin = admin;
  }

  @GetMapping
  @Operation(summary = "List inbound PSP events in a state (default ABANDONED), newest first")
  public List<EventResponse> list(
      @Parameter(required = true) @RequestHeader(value = AdminAuthorizer.HEADER, required = false)
          String token,
      @RequestParam(defaultValue = "ABANDONED") String state,
      @RequestParam(defaultValue = "50") int limit) {
    admin.require(token);
    String wanted = state.toUpperCase(Locale.ROOT);
    if (!STATES.contains(wanted)) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          "state must be one of " + STATES.stream().sorted().toList() + ".");
    }
    return webhooks.eventsInState(wanted, Math.max(1, Math.min(limit, 200))).stream()
        .map(EventResponse::from)
        .toList();
  }

  @PostMapping("/{eventId}/replay")
  @Operation(
      summary = "Replay an ABANDONED inbound event",
      description =
          "Applies the stored event if its operation now exists. Safe to repeat: a second call"
              + " answers already_processed. 409 if the operation still does not exist or the"
              + " event is not ABANDONED; nothing changes then.")
  public Map<String, String> replay(
      @Parameter(required = true) @RequestHeader(value = AdminAuthorizer.HEADER, required = false)
          String token,
      @PathVariable String eventId) {
    admin.require(token);
    var result = inbound.replayAbandoned(eventId);
    return Map.of("event_id", eventId, "result", result.name().toLowerCase(Locale.ROOT));
  }
}
