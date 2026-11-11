package com.payflow.web;

import com.payflow.application.IdempotentExecutor.ResponseSpec;
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
import org.springframework.http.HttpStatus;
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

  private final PaymentService service;
  private final IdempotentMutations mutations;
  private final StrictJson json;

  public PaymentController(PaymentService service, IdempotentMutations mutations, StrictJson json) {
    this.service = service;
    this.mutations = mutations;
    this.json = json;
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
      @Parameter(required = true)
          @RequestHeader(value = IdempotentMutations.MERCHANT_HEADER, required = false)
          String merchantId) {
    return PaymentResponse.from(service.get(IdempotentMutations.requireMerchant(merchantId), id));
  }

  @GetMapping("/{id}/ledger")
  @Operation(summary = "Fetch the double-entry ledger postings of a payment")
  public LedgerResponse ledger(
      @PathVariable String id,
      @Parameter(required = true)
          @RequestHeader(value = IdempotentMutations.MERCHANT_HEADER, required = false)
          String merchantId) {
    return LedgerResponse.from(service.ledger(IdempotentMutations.requireMerchant(merchantId), id));
  }
}
