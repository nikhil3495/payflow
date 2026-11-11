package com.payflow.application;

import com.payflow.infrastructure.IdempotencyRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Deletes expired idempotency keys in small batches so it never holds long locks. */
@Component
public class IdempotencyPurgeJob {

  static final int BATCH_SIZE = 1_000;
  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

  private final IdempotencyRepository repository;
  private final Clock clock;
  private final TransactionTemplate tx;

  public IdempotencyPurgeJob(
      IdempotencyRepository repository, Clock clock, PlatformTransactionManager txManager) {
    this.repository = repository;
    this.clock = clock;
    this.tx = new TransactionTemplate(txManager);
  }

  /** Returns the number of rows removed. */
  public int purgeExpired() {
    int total = 0;
    int deleted;
    do {
      Integer batch = tx.execute(status -> repository.purgeExpired(clock.instant(), BATCH_SIZE));
      deleted = batch == null ? 0 : batch;
      total += deleted;
    } while (deleted == BATCH_SIZE);
    if (total > 0) {
      LOG.info("purged {} expired idempotency keys", total);
    }
    return total;
  }
}
