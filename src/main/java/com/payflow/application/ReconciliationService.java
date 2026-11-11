package com.payflow.application;

import com.payflow.domain.Amounts;
import com.payflow.domain.Direction;
import com.payflow.domain.LedgerAccountType;
import com.payflow.domain.LedgerPostings;
import com.payflow.domain.Payment;
import com.payflow.domain.PaymentOperation;
import com.payflow.infrastructure.LedgerRepository;
import com.payflow.infrastructure.LedgerRepository.PaymentEntry;
import com.payflow.infrastructure.OperationRepository;
import com.payflow.infrastructure.PaymentRepository;
import com.payflow.infrastructure.ReconciliationRepository;
import com.payflow.infrastructure.ReconciliationRepository.Discrepancy;
import com.payflow.infrastructure.ReconciliationRepository.Run;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recomputes every payment's expected state from its operations and compares it with what is
 * actually stored: the payment's money counters and the net position of each ledger account.
 *
 * <p>The whole comparison runs in one REPEATABLE READ snapshot so a payment that is being modified
 * while the job runs can never be seen half-way and reported as a false discrepancy. Operations the
 * PSP reported as failed are excluded from the expectation, so a ledger that still counts them is
 * reported.
 */
@Service
public class ReconciliationService {

  public static final String AGGREGATE_MISMATCH = "AGGREGATE_MISMATCH";
  public static final String LEDGER_POSITION_MISMATCH = "LEDGER_POSITION_MISMATCH";

  private static final Logger LOG = LoggerFactory.getLogger(ReconciliationService.class);
  private static final int PAGE_SIZE = 500;
  private static final UUID MIN_UUID = new UUID(0L, 0L);

  public record RunDetail(Run run, List<Discrepancy> discrepancies) {
    public RunDetail {
      discrepancies = List.copyOf(discrepancies);
    }
  }

  private final PaymentRepository payments;
  private final OperationRepository operations;
  private final LedgerRepository ledger;
  private final ReconciliationRepository repository;
  private final IdGenerator ids;
  private final Clock clock;
  private final MeterRegistry meters;
  private final TransactionTemplate runTx;
  private final TransactionTemplate plainTx;
  private final AtomicLong lastDiscrepancies;

  public ReconciliationService(
      PaymentRepository payments,
      OperationRepository operations,
      LedgerRepository ledger,
      ReconciliationRepository repository,
      IdGenerator ids,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager txManager) {
    this.payments = payments;
    this.operations = operations;
    this.ledger = ledger;
    this.repository = repository;
    this.ids = ids;
    this.clock = clock;
    this.meters = meters;
    this.runTx = new TransactionTemplate(txManager);
    this.runTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.plainTx = new TransactionTemplate(txManager);
    this.lastDiscrepancies =
        meters.gauge("payflow.reconciliation.last.discrepancies", new AtomicLong());
  }

  /** Runs a full reconciliation and returns its summary. Safe to call from several instances. */
  public Run run() {
    UUID runId = ids.next();
    plainTx.executeWithoutResult(s -> repository.insertRun(runId, clock.instant()));
    try {
      Run result = runTx.execute(status -> reconcileAll(runId));
      if (result == null) {
        throw new IllegalStateException("reconciliation produced no result");
      }
      meters
          .counter(
              "payflow.reconciliation.runs",
              "state",
              result.state().toLowerCase(java.util.Locale.ROOT))
          .increment();
      lastDiscrepancies.set(result.discrepancyCount());
      if (result.discrepancyCount() > 0) {
        LOG.error(
            "reconciliation run {} found {} discrepancies across {} payments",
            runId,
            result.discrepancyCount(),
            result.paymentsChecked());
      } else {
        LOG.info(
            "reconciliation run {} checked {} payments, no discrepancies",
            runId,
            result.paymentsChecked());
      }
      return result;
    } catch (RuntimeException e) {
      LOG.error("reconciliation run {} failed", runId, e);
      plainTx.executeWithoutResult(
          s ->
              repository.finishRun(
                  runId,
                  ReconciliationRepository.FAILED,
                  clock.instant(),
                  0,
                  0,
                  e.getClass().getSimpleName()));
      throw e;
    }
  }

  /** The comparison itself; runs inside the REPEATABLE READ transaction. */
  private Run reconcileAll(UUID runId) {
    if (!repository.tryLockRun()) {
      repository.finishRun(
          runId,
          ReconciliationRepository.FAILED,
          clock.instant(),
          0,
          0,
          "skipped: another reconciliation run is in progress");
      return repository.findRun(runId).orElseThrow();
    }
    long checked = 0;
    long found = 0;
    UUID after = MIN_UUID;
    while (true) {
      List<Payment> page = payments.pageAfter(after, PAGE_SIZE);
      if (page.isEmpty()) {
        break;
      }
      found += reconcilePage(runId, page);
      checked += page.size();
      after = page.get(page.size() - 1).id();
    }
    repository.finishRun(
        runId, ReconciliationRepository.COMPLETED, clock.instant(), checked, found, null);
    return repository.findRun(runId).orElseThrow();
  }

  public List<Run> latestRuns(int limit) {
    return repository.latestRuns(limit);
  }

  public RunDetail detail(UUID runId) {
    Run run =
        repository
            .findRun(runId)
            .orElseThrow(
                () -> new ApiException(ErrorCode.NOT_FOUND, "No such reconciliation run."));
    return new RunDetail(run, repository.discrepancies(runId));
  }

  private long reconcilePage(UUID runId, List<Payment> page) {
    List<UUID> ids = page.stream().map(Payment::id).toList();
    Map<UUID, List<PaymentOperation>> opsByPayment =
        operations.findByPayments(ids).stream()
            .collect(Collectors.groupingBy(PaymentOperation::paymentId));
    Map<UUID, List<PaymentEntry>> entriesByPayment =
        ledger.entriesForPayments(ids).stream()
            .collect(Collectors.groupingBy(PaymentEntry::paymentId));

    long found = 0;
    Instant now = clock.instant();
    for (Payment payment : page) {
      List<Discrepancy> issues =
          check(
              runId,
              payment,
              opsByPayment.getOrDefault(payment.id(), List.of()),
              entriesByPayment.getOrDefault(payment.id(), List.of()),
              now);
      issues.forEach(repository::insertDiscrepancy);
      found += issues.size();
    }
    return found;
  }

  /** Pure comparison of one payment; package-private so it can be exercised directly. */
  List<Discrepancy> check(
      UUID runId,
      Payment payment,
      List<PaymentOperation> operations,
      List<PaymentEntry> entries,
      Instant now) {
    List<Discrepancy> issues = new ArrayList<>();
    List<PaymentOperation> effective =
        operations.stream()
            .filter(
                op ->
                    PaymentOperation.SUCCEEDED.equals(op.state())
                        && !PaymentOperation.PSP_FAILED.equals(op.pspStatus()))
            .toList();

    long captured = 0;
    long refunded = 0;
    long voided = 0;
    Map<LedgerAccountType, Long> expected = new EnumMap<>(LedgerAccountType.class);
    try {
      for (PaymentOperation op : effective) {
        switch (op.type()) {
          case CAPTURE -> {
            captured = Amounts.add(captured, op.amountMinor());
            merge(expected, LedgerPostings.forCapture(op.amountMinor(), op.feeMinor()));
          }
          case REFUND -> {
            refunded = Amounts.add(refunded, op.amountMinor());
            merge(expected, LedgerPostings.forRefund(op.amountMinor()));
          }
          case VOID -> voided = Amounts.add(voided, op.amountMinor());
          case AUTHORIZE -> {}
        }
      }
    } catch (RuntimeException unreconcilable) {
      issues.add(
          discrepancy(runId, payment, AGGREGATE_MISMATCH, "operations_unsummable", 0, 0, now));
      return issues;
    }

    compare(issues, runId, payment, "captured_minor", captured, payment.capturedMinor(), now);
    compare(issues, runId, payment, "refunded_minor", refunded, payment.refundedMinor(), now);
    compare(issues, runId, payment, "voided_minor", voided, payment.voidedMinor(), now);

    Map<LedgerAccountType, Long> actual = new EnumMap<>(LedgerAccountType.class);
    try {
      for (PaymentEntry entry : entries) {
        long signed =
            entry.direction() == Direction.DEBIT ? entry.amountMinor() : -entry.amountMinor();
        actual.merge(entry.accountType(), signed, Amounts::add);
      }
    } catch (RuntimeException unsummable) {
      issues.add(
          discrepancy(runId, payment, LEDGER_POSITION_MISMATCH, "entries_unsummable", 0, 0, now));
      return issues;
    }
    for (LedgerAccountType type : LedgerAccountType.values()) {
      long want = expected.getOrDefault(type, 0L);
      long have = actual.getOrDefault(type, 0L);
      if (want != have) {
        issues.add(
            discrepancy(runId, payment, LEDGER_POSITION_MISMATCH, type.name(), want, have, now));
      }
    }
    return issues;
  }

  private static void merge(
      Map<LedgerAccountType, Long> target, List<LedgerPostings.EntryDraft> drafts) {
    LedgerPostings.netDebitByAccount(drafts).forEach((k, v) -> target.merge(k, v, Amounts::add));
  }

  private void compare(
      List<Discrepancy> issues,
      UUID runId,
      Payment payment,
      String subject,
      long expected,
      long actual,
      Instant now) {
    if (expected != actual) {
      issues.add(discrepancy(runId, payment, AGGREGATE_MISMATCH, subject, expected, actual, now));
    }
  }

  private Discrepancy discrepancy(
      UUID runId,
      Payment payment,
      String kind,
      String subject,
      long expected,
      long actual,
      Instant now) {
    return new Discrepancy(ids.next(), runId, payment.id(), kind, subject, expected, actual, now);
  }
}
