package com.payflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.support.IntegrationTest;
import com.payflow.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;

class RetryingTransactionRunnerTest extends IntegrationTest {

  @Autowired PlatformTransactionManager transactionManager;

  private final List<Duration> sleeps = new ArrayList<>();

  private RetryingTransactionRunner runner(int maxAttempts) {
    return new RetryingTransactionRunner(
        transactionManager,
        TestProperties.build(false, false, maxAttempts),
        RandomGenerator.getDefault(),
        new SimpleMeterRegistry(),
        sleeps::add);
  }

  private static RuntimeException serializationFailure() {
    return new CannotAcquireLockException(
        "x", new SQLException("could not serialize access due to concurrent update", "40001"));
  }

  @Test
  void workRunsInASerializableTransaction() {
    String isolation =
        runner(3)
            .runSerializable(() -> jdbc.queryForObject("SHOW transaction_isolation", String.class));
    assertThat(isolation).isEqualTo("serializable");
  }

  @Test
  void serializationFailuresAreRetriedWithJitteredBackoffUntilSuccess() {
    AtomicInteger calls = new AtomicInteger();
    String result =
        runner(8)
            .runSerializable(
                () -> {
                  if (calls.incrementAndGet() < 4) {
                    throw serializationFailure();
                  }
                  return "ok";
                });
    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(4);
    assertThat(sleeps).hasSize(3);
    // Full jitter: the n-th pause is within [0, min(cap, base * 2^(n-1))] = [0, 10ms * 2^(n-1)].
    for (int i = 0; i < sleeps.size(); i++) {
      assertThat(sleeps.get(i)).isBetween(Duration.ZERO, Duration.ofMillis(10L << i));
    }
  }

  @Test
  void deadlocksAreRetriedToo() {
    AtomicInteger calls = new AtomicInteger();
    String result =
        runner(5)
            .runSerializable(
                () -> {
                  if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException(
                        "x", new SQLException("deadlock detected", "40P01"));
                  }
                  return "ok";
                });
    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(2);
  }

  @Test
  void aSqlStateBuriedInTheCauseChainIsRecognized() {
    AtomicInteger calls = new AtomicInteger();
    runner(5)
        .runSerializable(
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException(
                    "wrapped",
                    new RuntimeException("again", new SQLException("serialization", "40001")));
              }
              return null;
            });
    assertThat(calls).hasValue(2);
  }

  @Test
  void retriesAreBoundedAndExhaustionIsA503NotA500() {
    AtomicInteger calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                runner(6)
                    .runSerializable(
                        () -> {
                          calls.incrementAndGet();
                          throw serializationFailure();
                        }))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_BUSY);
              assertThat(e.code().status()).isEqualTo(503);
              assertThat(e.retryAfterSeconds()).isEqualTo(1);
            });
    assertThat(calls).hasValue(6);
    assertThat(sleeps).hasSize(5); // no pause after the final attempt
  }

  @Test
  void otherFailuresAreNotRetried() {
    for (RuntimeException failure :
        List.of(
            new IllegalStateException("boom"),
            new DuplicateKeyException("dup", new SQLException("unique", "23505")),
            new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "no"),
            new com.payflow.domain.DomainViolation(
                com.payflow.domain.DomainError.INVALID_AMOUNT, "no"))) {
      AtomicInteger calls = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  runner(5)
                      .runSerializable(
                          () -> {
                            calls.incrementAndGet();
                            throw failure;
                          }))
          .isSameAs(failure);
      assertThat(calls).as(failure.getClass().getSimpleName()).hasValue(1);
    }
    assertThat(sleeps).isEmpty();
  }

  @Test
  void eachAttemptGetsAFreshTransactionAndARolledBackAttemptLeavesNothing() {
    jdbc.execute("CREATE TABLE IF NOT EXISTS runner_probe (id text PRIMARY KEY)");
    String id = "probe-" + System.nanoTime();
    AtomicInteger calls = new AtomicInteger();
    runner(4)
        .runSerializable(
            () -> {
              jdbc.update("INSERT INTO runner_probe (id) VALUES (?)", id + "-" + calls.get());
              if (calls.incrementAndGet() < 3) {
                throw serializationFailure();
              }
              return null;
            });
    Long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM runner_probe WHERE id LIKE ?", Long.class, id + "-%");
    assertThat(rows).isEqualTo(1); // only the successful attempt's insert survived
  }

  @Test
  void anInterruptDuringBackoffStopsRetryingAndKeepsTheInterruptFlag() {
    RetryingTransactionRunner interrupted =
        new RetryingTransactionRunner(
            transactionManager,
            TestProperties.defaults(),
            RandomGenerator.getDefault(),
            new SimpleMeterRegistry(),
            d -> {
              throw new InterruptedException("stop");
            });
    try {
      assertThatThrownBy(
              () ->
                  interrupted.runSerializable(
                      () -> {
                        throw serializationFailure();
                      }))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_BUSY));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear the flag for other tests
    }
  }

  /**
   * A genuine PostgreSQL write-skew: both transactions read the table sum and then write different
   * rows. SERIALIZABLE aborts one with 40001; the runner re-runs it and both end up committed.
   */
  @Test
  void aRealSerializationFailureFromPostgresIsRetriedToSuccess() throws Exception {
    jdbc.execute("CREATE TABLE IF NOT EXISTS skew (id int PRIMARY KEY, v int NOT NULL)");
    jdbc.update("DELETE FROM skew");
    jdbc.update("INSERT INTO skew VALUES (1, 0), (2, 0)");
    RetryingTransactionRunner runner = runner(10);
    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    List<Thread> threads = new ArrayList<>();
    for (int row = 1; row <= 2; row++) {
      int target = row;
      Thread thread =
          Thread.ofPlatform()
              .start(
                  () -> {
                    boolean[] first = {true};
                    try {
                      runner.runSerializable(
                          () -> {
                            attempts.incrementAndGet();
                            jdbc.queryForObject("SELECT sum(v) FROM skew", Long.class);
                            if (first[0]) {
                              first[0] = false;
                              try {
                                barrier.await(10, TimeUnit.SECONDS);
                              } catch (Exception e) {
                                throw new IllegalStateException(e);
                              }
                            }
                            jdbc.update("UPDATE skew SET v = v + 1 WHERE id = ?", target);
                            return null;
                          });
                    } catch (Throwable t) {
                      failure.set(t);
                    }
                  });
      threads.add(thread);
    }
    for (Thread t : threads) {
      t.join(30_000);
    }

    assertThat(failure.get()).isNull();
    assertThat(attempts.get()).as("one transaction had to be retried").isGreaterThan(2);
    assertThat(jdbc.queryForObject("SELECT sum(v) FROM skew", Long.class)).isEqualTo(2);
  }

  @Test
  void errorCodesAreStableAndEveryDomainErrorMapsToOne() {
    for (com.payflow.domain.DomainError error : com.payflow.domain.DomainError.values()) {
      ErrorCode mapped = ErrorCode.from(error);
      assertThat(mapped.name()).isEqualTo(error.name());
      assertThat(mapped.status()).isBetween(400, 499);
    }
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(code.typeUri()).startsWith("urn:payflow:problem:").doesNotContain("_");
      assertThat(code.title()).isNotBlank();
      assertThat(code.status()).isBetween(400, 599);
    }
  }
}
