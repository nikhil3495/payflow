package com.payflow.application;

import com.payflow.config.PayflowProperties;
import com.payflow.infrastructure.TransientConflictException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a SERIALIZABLE transaction and re-runs the whole transaction on PostgreSQL
 * serialization failures (SQLState 40001) and deadlocks (40P01). Retries are bounded and jittered;
 * exhausting them yields a 503, never a 500, because the request had no effect and may be retried.
 *
 * <p>Programmatic transactions are used on purpose: there is no proxy, so there is no
 * self-invocation trap and the transaction boundary is visible at the call site.
 */
@Component
public class RetryingTransactionRunner {

  /** Pauses between attempts; replaceable so tests do not really sleep. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private static final Logger LOG = LoggerFactory.getLogger(RetryingTransactionRunner.class);
  private static final String SERIALIZATION_FAILURE = "40001";
  private static final String DEADLOCK_DETECTED = "40P01";
  private static final int MAX_CAUSE_DEPTH = 16;

  private final TransactionTemplate template;
  private final int maxAttempts;
  private final BackoffPolicy backoff;
  private final RandomGenerator random;
  private final Sleeper sleeper;
  private final Counter retries;
  private final Counter exhausted;

  @Autowired
  public RetryingTransactionRunner(
      PlatformTransactionManager transactionManager,
      PayflowProperties properties,
      RandomGenerator random,
      MeterRegistry meters) {
    this(transactionManager, properties, random, meters, duration -> Thread.sleep(duration));
  }

  public RetryingTransactionRunner(
      PlatformTransactionManager transactionManager,
      PayflowProperties properties,
      RandomGenerator random,
      MeterRegistry meters,
      Sleeper sleeper) {
    this.template = new TransactionTemplate(transactionManager);
    this.template.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.maxAttempts = properties.retry().maxAttempts();
    this.backoff =
        new BackoffPolicy(properties.retry().baseBackoff(), properties.retry().maxBackoff());
    this.random = random;
    this.sleeper = sleeper;
    this.retries = meters.counter("payflow.tx.retries");
    this.exhausted = meters.counter("payflow.tx.retries.exhausted");
  }

  public <T> T runSerializable(Supplier<T> work) {
    for (int attempt = 1; ; attempt++) {
      try {
        return template.execute(status -> work.get());
      } catch (RuntimeException e) {
        if (!isRetryable(e)) {
          throw e;
        }
        retries.increment();
        if (attempt >= maxAttempts) {
          exhausted.increment();
          LOG.warn("giving up after {} serialization-conflict attempts", attempt);
          throw new ApiException(
              ErrorCode.SERVICE_BUSY,
              "The request could not be completed due to contention; retry it.",
              java.util.List.of(),
              1);
        }
        pause(attempt);
      }
    }
  }

  private void pause(int attempt) {
    try {
      sleeper.sleep(backoff.delayFor(attempt, random));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(ErrorCode.SERVICE_BUSY, "Interrupted while retrying.");
    }
  }

  static boolean isRetryable(Throwable error) {
    Throwable current = error;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof TransientConflictException
          || current instanceof ConcurrencyFailureException) {
        return true;
      }
      if (current instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (SERIALIZATION_FAILURE.equals(state) || DEADLOCK_DETECTED.equals(state)) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }
}
