package com.payflow.web;

import com.payflow.application.ApiException;
import com.payflow.application.ErrorCode;
import com.payflow.application.IdempotentExecutor;
import com.payflow.application.IdempotentExecutor.ResponseSpec;
import com.payflow.application.IdempotentExecutor.StoredResponse;
import com.payflow.application.PaymentService;
import com.payflow.application.PaymentService.AuthorizeCommand;
import com.payflow.application.PaymentService.MoneyCommand;
import com.payflow.application.StrictJson;
import com.payflow.web.dto.AuthorizeRequest;
import com.payflow.web.dto.LedgerResponse;
import com.payflow.web.dto.MoneyRequest;
import com.payflow.web.dto.OperationResponse;
import com.payflow.web.dto.PaymentResponse;
import com.payflow.web.dto.VoidRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

  public static final String MERCHANT_HEADER = "X-Merchant-Id";
  public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
  public static final String REPLAY_HEADER = "Idempotency-Replayed";

  private static final Pattern MERCHANT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
  private static final Pattern KEY = Pattern.compile("^[\\x21-\\x7E]{1,255}$");

  private final PaymentService service;
  private final IdempotentExecutor executor;
  private final StrictJson json;
  private final PaymentMetrics metrics;

  public PaymentController(
      PaymentService service,
      IdempotentExecutor executor,
      StrictJson json,
      PaymentMetrics metrics) {
    this.service = service;
    this.executor = executor;
    this.json = json;
    this.metrics = metrics;
  }

  @PostMapping
  @Operation(
      summary = "Authorize a payment",
      description =
          "Places a hold for the given amount. Idempotent via the Idempotency-Key header.",
      requestBody =
          @RequestBody(
              content = @Content(schema = @Schema(implementation = AuthorizeRequest.class))))
  public ResponseEntity<byte[]> authorize(
      @Parameter(required = true) @RequestHeader(value = MERCHANT_HEADER, required = false)
          String merchantId,
      @Parameter(required = true) @RequestHeader(value = IDEMPOTENCY_HEADER, required = false)
          String key,
      @Parameter(hidden = true)
          @org.springframework.web.bind.annotation.RequestBody(required = false)
          byte[] body,
      HttpServletRequest request) {
    return mutate(
        "authorize",
        merchantId,
        key,
        request,
        body,
        () -> {
          AuthorizeRequest r = json.parse(body, AuthorizeRequest.class);
          var outcome =
              service.authorize(
                  merchantId,
                  new AuthorizeCommand(
                      r.amountMinor(), r.currency(), r.paymentMethodToken(), r.reference()));
          return new ResponseSpec(
              HttpStatus.CREATED.value(), PaymentResponse.from(outcome.payment()));
        });
  }

  @PostMapping("/{id}/captures")
  @Operation(
      summary = "Capture an authorized amount (full or partial)",
      requestBody =
          @RequestBody(content = @Content(schema = @Schema(implementation = MoneyRequest.class))))
  public ResponseEntity<byte[]> capture(
      @PathVariable String id,
      @Parameter(required = true) @RequestHeader(value = MERCHANT_HEADER, required = false)
          String merchantId,
      @Parameter(required = true) @RequestHeader(value = IDEMPOTENCY_HEADER, required = false)
          String key,
      @Parameter(hidden = true)
          @org.springframework.web.bind.annotation.RequestBody(required = false)
          byte[] body,
      HttpServletRequest request) {
    return mutate(
        "capture",
        merchantId,
        key,
        request,
        body,
        () -> {
          MoneyRequest r = json.parse(body, MoneyRequest.class);
          var outcome =
              service.capture(merchantId, id, new MoneyCommand(r.amountMinor(), r.currency()));
          return new ResponseSpec(HttpStatus.CREATED.value(), OperationResponse.from(outcome));
        });
  }

  @PostMapping("/{id}/refunds")
  @Operation(
      summary = "Refund captured money (full or partial)",
      requestBody =
          @RequestBody(content = @Content(schema = @Schema(implementation = MoneyRequest.class))))
  public ResponseEntity<byte[]> refund(
      @PathVariable String id,
      @Parameter(required = true) @RequestHeader(value = MERCHANT_HEADER, required = false)
          String merchantId,
      @Parameter(required = true) @RequestHeader(value = IDEMPOTENCY_HEADER, required = false)
          String key,
      @Parameter(hidden = true)
          @org.springframework.web.bind.annotation.RequestBody(required = false)
          byte[] body,
      HttpServletRequest request) {
    return mutate(
        "refund",
        merchantId,
        key,
        request,
        body,
        () -> {
          MoneyRequest r = json.parse(body, MoneyRequest.class);
          var outcome =
              service.refund(merchantId, id, new MoneyCommand(r.amountMinor(), r.currency()));
          return new ResponseSpec(HttpStatus.CREATED.value(), OperationResponse.from(outcome));
        });
  }

  @PostMapping("/{id}/voids")
  @Operation(
      summary = "Void the uncaptured remainder of an authorization",
      requestBody =
          @RequestBody(
              required = false,
              content = @Content(schema = @Schema(implementation = VoidRequest.class))))
  public ResponseEntity<byte[]> voidPayment(
      @PathVariable String id,
      @Parameter(required = true) @RequestHeader(value = MERCHANT_HEADER, required = false)
          String merchantId,
      @Parameter(required = true) @RequestHeader(value = IDEMPOTENCY_HEADER, required = false)
          String key,
      @Parameter(hidden = true)
          @org.springframework.web.bind.annotation.RequestBody(required = false)
          byte[] body,
      HttpServletRequest request) {
    return mutate(
        "void",
        merchantId,
        key,
        request,
        body,
        () -> {
          json.parseOptional(body, VoidRequest.class, VoidRequest.EMPTY);
          var outcome = service.voidPayment(merchantId, id);
          return new ResponseSpec(HttpStatus.CREATED.value(), OperationResponse.from(outcome));
        });
  }

  @GetMapping("/{id}")
  @Operation(summary = "Fetch a payment")
  public PaymentResponse get(
      @PathVariable String id,
      @Parameter(required = true) @RequestHeader(value = MERCHANT_HEADER, required = false)
          String merchantId) {
    return PaymentResponse.from(service.get(requireMerchant(merchantId), id));
  }

  @GetMapping("/{id}/ledger")
  @Operation(summary = "Fetch the double-entry ledger postings of a payment")
  public LedgerResponse ledger(
      @PathVariable String id,
      @Parameter(required = true) @RequestHeader(value = MERCHANT_HEADER, required = false)
          String merchantId) {
    return LedgerResponse.from(service.ledger(requireMerchant(merchantId), id));
  }

  private ResponseEntity<byte[]> mutate(
      String operation,
      String merchantId,
      String key,
      HttpServletRequest request,
      byte[] body,
      Supplier<ResponseSpec> work) {
    long started = System.nanoTime();
    try {
      requireMerchant(merchantId);
      requireKey(key);
      requireJsonContentType(request, body);
      byte[] raw = body == null ? new byte[0] : body;
      StoredResponse stored =
          executor.execute(
              merchantId, key, request.getMethod() + " " + request.getRequestURI(), raw, work);
      metrics.record(operation, stored.status(), stored.replayed(), elapsed(started));
      ResponseEntity.BodyBuilder response =
          ResponseEntity.status(stored.status())
              .contentType(MediaType.parseMediaType(stored.contentType()));
      if (stored.replayed()) {
        response.header(REPLAY_HEADER, "true");
      }
      return response.body(stored.body().getBytes(StandardCharsets.UTF_8));
    } catch (ApiException e) {
      metrics.record(operation, e.code().status(), false, elapsed(started));
      throw e;
    }
  }

  private static Duration elapsed(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos);
  }

  static String requireMerchant(String merchantId) {
    if (merchantId == null || !MERCHANT.matcher(merchantId).matches()) {
      throw new ApiException(
          ErrorCode.MISSING_MERCHANT_ID,
          MERCHANT_HEADER + " must be 1-64 characters of [A-Za-z0-9_-].");
    }
    return merchantId;
  }

  static void requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new ApiException(
          ErrorCode.MISSING_IDEMPOTENCY_KEY, IDEMPOTENCY_HEADER + " header is required.");
    }
    if (!KEY.matcher(key).matches()) {
      throw new ApiException(
          ErrorCode.INVALID_IDEMPOTENCY_KEY,
          IDEMPOTENCY_HEADER + " must be 1-255 printable ASCII characters without spaces.");
    }
  }

  private static void requireJsonContentType(HttpServletRequest request, byte[] body) {
    if (body == null || body.length == 0) {
      return;
    }
    boolean json;
    try {
      String contentType = request.getContentType();
      json =
          contentType != null
              && MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON);
    } catch (InvalidMediaTypeException e) {
      json = false;
    }
    if (!json) {
      throw new ApiException(
          ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Use Content-Type: application/json.");
    }
  }
}
