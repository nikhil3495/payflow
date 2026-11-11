package com.payflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.config.PayflowProperties;
import com.payflow.domain.DomainViolation;
import com.payflow.infrastructure.IdempotencyRepository;
import com.payflow.infrastructure.IdempotencyRepository.Record;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a mutating request exactly once per (merchant, Idempotency-Key).
 *
 * <p>Everything happens inside ONE serializable transaction:
 *
 * <ol>
 *   <li>take a transaction-scoped advisory lock on (merchant, key); if another request holds it,
 *       that request is in flight and we answer 409;
 *   <li>look the key up: same endpoint + fingerprint replays the stored response, anything else is
 *       a 422 key-reuse error;
 *   <li>otherwise insert an IN_FLIGHT row and run the work;
 *   <li>a deterministic client error (4xx) raised by the work is stored and replayed - the work is
 *       rolled back to a savepoint first, so it leaves no partial writes;
 *   <li>any other failure (5xx, crash, lost connection) rolls the whole transaction back, including
 *       the IN_FLIGHT row, so the key stays usable and is never poisoned;
 *   <li>on success the serialized response is stored and the row marked COMPLETED before commit.
 * </ol>
 */
@Component
public class IdempotentExecutor {

  /** What a unit of work wants to answer with. */
  public record ResponseSpec(int status, Object body) {}

  /** A response as stored (or replayed). The body is exactly the bytes sent to the client. */
  public record StoredResponse(int status, String contentType, String body, boolean replayed) {}

  public static final String JSON = "application/json";
  public static final int RETRY_AFTER_SECONDS = 1;

  private final RetryingTransactionRunner runner;
  private final IdempotencyRepository repository;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final IdGenerator ids;
  private final PayflowProperties properties;
  private final TransactionTemplate nested;

  public IdempotentExecutor(
      RetryingTransactionRunner runner,
      IdempotencyRepository repository,
      ObjectMapper mapper,
      Clock clock,
      IdGenerator ids,
      PayflowProperties properties,
      PlatformTransactionManager transactionManager) {
    this.runner = runner;
    this.repository = repository;
    this.mapper = mapper;
    this.clock = clock;
    this.ids = ids;
    this.properties = properties;
    this.nested = new TransactionTemplate(transactionManager);
    this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  /**
   * @param endpoint "METHOD /path" of the request, compared on replay
   * @param body raw request bytes, fingerprinted for key-reuse detection
   */
  public StoredResponse execute(
      String merchantId, String key, String endpoint, byte[] body, Supplier<ResponseSpec> work) {
    String fingerprint = RequestFingerprint.of(body);
    return runner.runSerializable(() -> executeOnce(merchantId, key, endpoint, fingerprint, work));
  }

  private StoredResponse executeOnce(
      String merchantId,
      String key,
      String endpoint,
      String fingerprint,
      Supplier<ResponseSpec> work) {
    Instant now = clock.instant();

    if (!repository.tryLock(merchantId, key)) {
      throw inProgress();
    }

    Optional<Record> existing = repository.find(merchantId, key);
    if (existing.isPresent() && !existing.get().expiresAt().isAfter(now)) {
      repository.delete(existing.get().id());
      existing = Optional.empty();
    }
    if (existing.isPresent()) {
      return replayOrReject(existing.get(), endpoint, fingerprint);
    }

    var rowId = ids.next();
    repository.insertInFlight(
        rowId,
        merchantId,
        key,
        endpoint,
        fingerprint,
        now,
        now.plus(properties.idempotency().ttl()));

    ResponseSpec spec;
    IdempotencyContext.set(RequestFingerprint.sha256Hex(merchantId + "/" + key));
    try {
      spec =
          Objects.requireNonNull(nested.execute(status -> work.get()), "work returned no response");
    } catch (ApiException | DomainViolation failure) {
      ApiException api =
          failure instanceof DomainViolation violation
              ? ApiException.from(violation)
              : (ApiException) failure;
      if (!api.isClientError()) {
        throw api;
      }
      spec =
          new ResponseSpec(
              api.code().status(), Problem.of(api, endpoint.substring(endpoint.indexOf(' ') + 1)));
      String json = serialize(spec.body());
      repository.complete(rowId, spec.status(), Problem.MEDIA_TYPE, json);
      return new StoredResponse(spec.status(), Problem.MEDIA_TYPE, json, false);
    } finally {
      IdempotencyContext.clear();
    }

    String json = serialize(spec.body());
    repository.complete(rowId, spec.status(), JSON, json);
    return new StoredResponse(spec.status(), JSON, json, false);
  }

  private StoredResponse replayOrReject(Record stored, String endpoint, String fingerprint) {
    if (!stored.endpoint().equals(endpoint) || !stored.fingerprint().equals(fingerprint)) {
      throw new ApiException(
          ErrorCode.IDEMPOTENCY_KEY_REUSED,
          "This Idempotency-Key was already used with a different request.");
    }
    if (!IdempotencyRepository.COMPLETED.equals(stored.state())) {
      throw inProgress();
    }
    return new StoredResponse(
        stored.responseStatus(), stored.responseContentType(), stored.responseBody(), true);
  }

  private static ApiException inProgress() {
    return new ApiException(
        ErrorCode.REQUEST_IN_PROGRESS,
        "A request with this Idempotency-Key is still being processed.",
        java.util.List.of(),
        RETRY_AFTER_SECONDS);
  }

  private String serialize(Object body) {
    try {
      return mapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("response body could not be serialized", e);
    }
  }
}
