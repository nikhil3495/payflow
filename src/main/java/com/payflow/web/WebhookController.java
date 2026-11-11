package com.payflow.web;

import com.payflow.application.IdempotentExecutor.ResponseSpec;
import com.payflow.application.InboundWebhookService;
import com.payflow.application.StrictJson;
import com.payflow.application.WebhookEndpointService;
import com.payflow.web.dto.WebhookEndpointRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Webhooks")
public class WebhookController {

  public static final String SIGNATURE_HEADER = "PSP-Signature";

  private final InboundWebhookService inbound;
  private final WebhookEndpointService endpoints;
  private final IdempotentMutations mutations;
  private final StrictJson json;

  public WebhookController(
      InboundWebhookService inbound,
      WebhookEndpointService endpoints,
      IdempotentMutations mutations,
      StrictJson json) {
    this.inbound = inbound;
    this.endpoints = endpoints;
    this.mutations = mutations;
    this.json = json;
  }

  /**
   * Receives PSP events. The body is taken as raw bytes - the signature is verified over exactly
   * what was sent. Authenticity, not an Idempotency-Key, protects this endpoint; replays are
   * absorbed by the unique PSP event id.
   */
  @PostMapping("/v1/webhooks/psp")
  @Operation(
      summary = "Receive a PSP webhook",
      description =
          "Header PSP-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of \"<t>.\" + raw body>.")
  public ResponseEntity<Map<String, String>> receive(
      @Parameter(required = true) @RequestHeader(value = SIGNATURE_HEADER, required = false)
          String signature,
      @Parameter(hidden = true)
          @org.springframework.web.bind.annotation.RequestBody(required = false)
          byte[] body) {
    InboundWebhookService.Result result =
        inbound.handle(body == null ? new byte[0] : body, signature);
    return ResponseEntity.ok(Map.of("status", result.name().toLowerCase(Locale.ROOT)));
  }

  @PutMapping("/v1/webhook-endpoint")
  @Operation(
      summary = "Register or replace the merchant's webhook endpoint",
      requestBody =
          @RequestBody(
              content = @Content(schema = @Schema(implementation = WebhookEndpointRequest.class))))
  public ResponseEntity<byte[]> register(
      @Parameter(required = true)
          @RequestHeader(value = IdempotentMutations.MERCHANT_HEADER, required = false)
          String merchantId,
      @Parameter(required = true)
          @RequestHeader(value = IdempotentMutations.IDEMPOTENCY_HEADER, required = false)
          String key,
      @Parameter(hidden = true)
          @org.springframework.web.bind.annotation.RequestBody(required = false)
          byte[] body,
      HttpServletRequest request) {
    return mutations.execute(
        "webhook_endpoint",
        merchantId,
        key,
        request,
        body,
        () -> {
          WebhookEndpointRequest r = json.parse(body, WebhookEndpointRequest.class);
          boolean created = endpoints.register(merchantId, r.url(), r.signingSecret());
          return new ResponseSpec(created ? 201 : 200, Map.of("url", r.url(), "created", created));
        });
  }
}
