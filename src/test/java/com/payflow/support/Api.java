package com.payflow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Thin HTTP client over the running application. Thread-safe. */
public final class Api {

  public static final ObjectMapper JSON = new ObjectMapper();

  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  public record Response(int status, String body, java.net.http.HttpHeaders headers) {
    public JsonNode json() {
      try {
        return JSON.readTree(body);
      } catch (IOException e) {
        throw new IllegalStateException("response is not JSON: " + body, e);
      }
    }

    public String header(String name) {
      return headers.firstValue(name).orElse(null);
    }

    public String code() {
      return json().path("code").asText();
    }
  }

  private final String base;

  public Api(int port) {
    this.base = "http://localhost:" + port;
  }

  public Response authorize(String merchant, String key, long amount, String currency) {
    return post(
        "/v1/payments",
        merchant,
        key,
        """
        {"amount_minor": %d, "currency": "%s", "payment_method_token": "tok_visa"}"""
            .formatted(amount, currency));
  }

  /** Authorizes and returns the new payment id; fails the test if it does not succeed. */
  public String authorizeOk(String merchant, long amount, String currency) {
    Response r = authorize(merchant, "auth-" + System.nanoTime() + Math.random(), amount, currency);
    if (r.status() != 201) {
      throw new AssertionError("authorize failed: " + r.status() + " " + r.body());
    }
    return r.json().path("id").asText();
  }

  public Response capture(String merchant, String key, String paymentId, long amount, String cur) {
    return post("/v1/payments/" + paymentId + "/captures", merchant, key, money(amount, cur));
  }

  public Response refund(String merchant, String key, String paymentId, long amount, String cur) {
    return post("/v1/payments/" + paymentId + "/refunds", merchant, key, money(amount, cur));
  }

  public Response voidPayment(String merchant, String key, String paymentId) {
    return post("/v1/payments/" + paymentId + "/voids", merchant, key, null);
  }

  public Response get(String path, String merchant) {
    return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), merchant, null, null);
  }

  public Response post(String path, String merchant, String key, String body) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .POST(
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    return send(builder, merchant, key, null);
  }

  public Response postRaw(String path, Map<String, String> headers, byte[] body) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    headers.forEach(builder::header);
    return execute(builder.timeout(Duration.ofSeconds(60)).build());
  }

  public Response send(
      HttpRequest.Builder builder, String merchant, String key, Map<String, String> extra) {
    if (merchant != null) {
      builder.header("X-Merchant-Id", merchant);
    }
    if (key != null) {
      builder.header("Idempotency-Key", key);
    }
    if (extra != null) {
      extra.forEach(builder::header);
    }
    return execute(builder.timeout(Duration.ofSeconds(60)).build());
  }

  private static Response execute(HttpRequest request) {
    try {
      HttpResponse<String> response =
          CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new Response(response.statusCode(), response.body(), response.headers());
    } catch (IOException e) {
      throw new IllegalStateException("request failed: " + request.uri(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  public static String money(long amount, String currency) {
    return "{\"amount_minor\": %d, \"currency\": \"%s\"}".formatted(amount, currency);
  }
}
