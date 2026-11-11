package com.payflow.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

  public static final String IN_FLIGHT = "IN_FLIGHT";
  public static final String COMPLETED = "COMPLETED";

  public record Record(
      UUID id,
      String endpoint,
      String fingerprint,
      String state,
      Integer responseStatus,
      String responseContentType,
      String responseBody,
      Instant expiresAt) {}

  private final JdbcClient jdbc;

  public IdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Transaction-scoped advisory lock on (merchant, key); merchant ids never contain a slash, so the
   * scope string is unambiguous. While another transaction holds it, that request is in flight.
   * Released automatically at commit or rollback.
   */
  public boolean tryLock(String merchantId, String key) {
    Boolean acquired =
        jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:scope, 0))")
            .param("scope", merchantId + "/" + key)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(acquired);
  }

  public Optional<Record> find(String merchantId, String key) {
    return jdbc.sql(
            """
            SELECT id, endpoint, request_fingerprint, state, response_status,
                   response_content_type, response_body, expires_at
              FROM idempotency_keys
             WHERE merchant_id = :merchant AND idempotency_key = :key
            """)
        .param("merchant", merchantId)
        .param("key", key)
        .query(
            (rs, n) ->
                new Record(
                    Db.uuid(rs, "id"),
                    rs.getString("endpoint"),
                    rs.getString("request_fingerprint").trim(),
                    rs.getString("state"),
                    Db.integerOrNull(rs, "response_status"),
                    rs.getString("response_content_type"),
                    rs.getString("response_body"),
                    Db.instant(rs, "expires_at")))
        .optional();
  }

  /**
   * Inserts the IN_FLIGHT claim. A unique violation means a row committed after our snapshot
   * started, which a retry with a fresh snapshot resolves into a replay.
   */
  public void insertInFlight(
      UUID id,
      String merchantId,
      String key,
      String endpoint,
      String fingerprint,
      Instant now,
      Instant expiresAt) {
    try {
      jdbc.sql(
              """
              INSERT INTO idempotency_keys (id, merchant_id, idempotency_key, endpoint,
                request_fingerprint, state, created_at, expires_at)
              VALUES (:id, :merchant, :key, :endpoint, :fingerprint, 'IN_FLIGHT', :now, :expires)
              """)
          .param("id", id)
          .param("merchant", merchantId)
          .param("key", key)
          .param("endpoint", endpoint)
          .param("fingerprint", fingerprint)
          .param("now", Db.ts(now))
          .param("expires", Db.ts(expiresAt))
          .update();
    } catch (DuplicateKeyException e) {
      throw new TransientConflictException("idempotency key committed concurrently", e);
    }
  }

  public void complete(UUID id, int status, String contentType, String body) {
    int rows =
        jdbc.sql(
                """
                UPDATE idempotency_keys
                   SET state = 'COMPLETED', response_status = :status,
                       response_content_type = :contentType, response_body = :body
                 WHERE id = :id AND state = 'IN_FLIGHT'
                """)
            .param("status", status)
            .param("contentType", contentType)
            .param("body", body)
            .param("id", id)
            .update();
    if (rows != 1) {
      throw new IllegalStateException("idempotency row " + id + " was not in flight");
    }
  }

  public void delete(UUID id) {
    jdbc.sql("DELETE FROM idempotency_keys WHERE id = :id").param("id", id).update();
  }

  /** Deletes at most {@code limit} expired rows, skipping rows another transaction is touching. */
  public int purgeExpired(Instant now, int limit) {
    return jdbc.sql(
            """
            DELETE FROM idempotency_keys
             WHERE id IN (SELECT id FROM idempotency_keys
                           WHERE expires_at <= :now
                           ORDER BY expires_at
                           LIMIT :limit
                             FOR UPDATE SKIP LOCKED)
            """)
        .param("now", Db.ts(now))
        .param("limit", limit)
        .update();
  }
}
