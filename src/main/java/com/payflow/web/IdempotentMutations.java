package com.payflow.web;

import com.payflow.application.ApiException;
import com.payflow.application.ErrorCode;
import com.payflow.application.IdempotentExecutor;
import com.payflow.application.IdempotentExecutor.ResponseSpec;
import com.payflow.application.IdempotentExecutor.StoredResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Request-level plumbing shared by every idempotent mutating endpoint. */
@Component
public class IdempotentMutations {

  public static final String MERCHANT_HEADER = "X-Merchant-Id";
  public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
  public static final String REPLAY_HEADER = "Idempotency-Replayed";

  private static final Pattern MERCHANT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
  private static final Pattern KEY = Pattern.compile("^[\\x21-\\x7E]{1,255}$");

  private final IdempotentExecutor executor;
  private final PaymentMetrics metrics;

  public IdempotentMutations(IdempotentExecutor executor, PaymentMetrics metrics) {
    this.executor = executor;
    this.metrics = metrics;
  }

  /**
   * Validates the headers, runs {@code work} exactly once per (merchant, key) and renders the
   * stored response - the original or a byte-for-byte replay.
   */
  public ResponseEntity<byte[]> execute(
      String operation,
      String merchantId,
      String key,
      HttpServletRequest request,
      byte[] body,
      Supplier<ResponseSpec> work) {
    return execute(operation, null, merchantId, key, request, body, work);
  }

  /** As above, queueing concurrent requests for the same payment before they reach the database. */
  public ResponseEntity<byte[]> execute(
      String operation,
      String paymentId,
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
              merchantId,
              key,
              request.getMethod() + " " + request.getRequestURI(),
              raw,
              paymentId,
              work);
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

  public static String requireMerchant(String merchantId) {
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
