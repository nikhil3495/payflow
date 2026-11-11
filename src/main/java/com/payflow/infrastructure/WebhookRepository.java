package com.payflow.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence for inbound events, merchant endpoints, outbound deliveries and the dead letter. */
@Repository
public class WebhookRepository {

  public static final String PENDING = "PENDING";
  public static final String DELIVERED = "DELIVERED";
  public static final String DEAD = "DEAD";

  public record Endpoint(String merchantId, String url, String signingSecret) {}

  public record Delivery(
      UUID id, String merchantId, String eventType, String payload, int attemptCount) {}

  private final JdbcClient jdbc;

  public WebhookRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ---- merchant endpoints -------------------------------------------------------------

  public Optional<Endpoint> findEndpoint(String merchantId) {
    return jdbc.sql(
            "SELECT merchant_id, url, signing_secret FROM webhook_endpoints WHERE merchant_id = :m")
        .param("m", merchantId)
        .query(
            (rs, n) ->
                new Endpoint(
                    rs.getString("merchant_id"),
                    rs.getString("url"),
                    rs.getString("signing_secret")))
        .optional();
  }

  /** Creates or replaces a merchant's endpoint; returns true when it was newly created. */
  public boolean upsertEndpoint(String merchantId, String url, String secret, Instant now) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO webhook_endpoints (merchant_id, url, signing_secret, created_at, updated_at)
                VALUES (:m, :url, :secret, :now, :now)
                ON CONFLICT (merchant_id) DO NOTHING
                """)
            .param("m", merchantId)
            .param("url", url)
            .param("secret", secret)
            .param("now", Db.ts(now))
            .update();
    if (inserted == 1) {
      return true;
    }
    jdbc.sql(
            """
            UPDATE webhook_endpoints SET url = :url, signing_secret = :secret, updated_at = :now
             WHERE merchant_id = :m
            """)
        .param("m", merchantId)
        .param("url", url)
        .param("secret", secret)
        .param("now", Db.ts(now))
        .update();
    return false;
  }

  // ---- inbound events -------------------------------------------------------------------

  /** Atomic dedup on the PSP's event id: returns false if the event was already received. */
  public boolean insertEventIfAbsent(
      UUID id, String pspEventId, String type, String payload, Instant now) {
    return jdbc.sql(
                """
                INSERT INTO webhook_events (id, psp_event_id, event_type, payload, processing_state, received_at)
                VALUES (:id, :event, :type, :payload, 'IGNORED', :now)
                ON CONFLICT (psp_event_id) DO NOTHING
                """)
            .param("id", id)
            .param("event", pspEventId)
            .param("type", type)
            .param("payload", payload)
            .param("now", Db.ts(now))
            .update()
        == 1;
  }

  public void setEventState(UUID id, String state) {
    jdbc.sql("UPDATE webhook_events SET processing_state = :state WHERE id = :id")
        .param("state", state)
        .param("id", id)
        .update();
  }

  // ---- outbound deliveries --------------------------------------------------------------

  public void insertDelivery(
      UUID id, String merchantId, UUID paymentId, String eventType, String payload, Instant now) {
    jdbc.sql(
            """
            INSERT INTO webhook_deliveries (id, merchant_id, payment_id, event_type, payload, state,
              attempt_count, next_attempt_at, created_at)
            VALUES (:id, :merchant, :payment, :type, :payload, 'PENDING', 0, :now, :now)
            """)
        .param("id", id)
        .param("merchant", merchantId)
        .param("payment", paymentId)
        .param("type", eventType)
        .param("payload", payload)
        .param("now", Db.ts(now))
        .update();
  }

  /**
   * Claims due deliveries with a lease: rows are locked with SKIP LOCKED so concurrent dispatchers
   * never take the same row, and next_attempt_at is pushed to the lease expiry so a dispatcher that
   * dies mid-delivery simply lets the row become due again (at-least-once).
   */
  public List<Delivery> claimDue(Instant now, int limit, Instant leaseUntil) {
    return jdbc.sql(
            """
            UPDATE webhook_deliveries d
               SET next_attempt_at = :lease
             WHERE d.id IN (SELECT id FROM webhook_deliveries
                             WHERE state = 'PENDING' AND next_attempt_at <= :now
                             ORDER BY next_attempt_at
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED)
            RETURNING d.id, d.merchant_id, d.event_type, d.payload, d.attempt_count
            """)
        .param("lease", Db.ts(leaseUntil))
        .param("now", Db.ts(now))
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Delivery(
                    Db.uuid(rs, "id"),
                    rs.getString("merchant_id"),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getInt("attempt_count")))
        .list();
  }

  public void markDelivered(UUID id, int attempts, int status, Instant now) {
    jdbc.sql(
            """
            UPDATE webhook_deliveries
               SET state = 'DELIVERED', attempt_count = :attempts, last_status = :status,
                   last_error = NULL, delivered_at = :now
             WHERE id = :id AND state = 'PENDING'
            """)
        .param("attempts", attempts)
        .param("status", status)
        .param("now", Db.ts(now))
        .param("id", id)
        .update();
  }

  public void markRetry(
      UUID id, int attempts, Instant nextAttemptAt, Integer status, String error) {
    jdbc.sql(
            """
            UPDATE webhook_deliveries
               SET attempt_count = :attempts, next_attempt_at = :next, last_status = :status,
                   last_error = :error
             WHERE id = :id AND state = 'PENDING'
            """)
        .param("attempts", attempts)
        .param("next", Db.ts(nextAttemptAt))
        .param("status", status)
        .param("error", error)
        .param("id", id)
        .update();
  }

  /** Marks the delivery dead and lands it in the dead-letter table, atomically. */
  public void markDead(
      UUID id,
      String merchantId,
      String eventType,
      String payload,
      int attempts,
      Integer status,
      String error,
      Instant now) {
    int updated =
        jdbc.sql(
                """
                UPDATE webhook_deliveries
                   SET state = 'DEAD', attempt_count = :attempts, last_status = :status,
                       last_error = :error
                 WHERE id = :id AND state = 'PENDING'
                """)
            .param("attempts", attempts)
            .param("status", status)
            .param("error", error)
            .param("id", id)
            .update();
    if (updated == 0) {
      return;
    }
    jdbc.sql(
            """
            INSERT INTO webhook_dead_letter (id, delivery_id, merchant_id, event_type, payload,
              attempt_count, last_status, last_error, dead_at)
            VALUES (:dlq, :id, :merchant, :type, :payload, :attempts, :status, :error, :now)
            """)
        .param("dlq", UUID.randomUUID())
        .param("id", id)
        .param("merchant", merchantId)
        .param("type", eventType)
        .param("payload", payload)
        .param("attempts", attempts)
        .param("status", status)
        .param("error", error)
        .param("now", Db.ts(now))
        .update();
  }
}
