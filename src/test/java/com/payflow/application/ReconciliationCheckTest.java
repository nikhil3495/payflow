package com.payflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.domain.Direction;
import com.payflow.domain.LedgerAccountType;
import com.payflow.domain.OperationType;
import com.payflow.domain.Payment;
import com.payflow.domain.PaymentOperation;
import com.payflow.infrastructure.LedgerRepository.PaymentEntry;
import com.payflow.infrastructure.ReconciliationRepository.Discrepancy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The pure comparison inside reconciliation, without a database. */
class ReconciliationCheckTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID RUN = UUID.randomUUID();
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final ReconciliationService service =
      new ReconciliationService(
          null, null, null, null, new IdGenerator(CLOCK), CLOCK, new SimpleMeterRegistry(), null);

  private static Payment payment(long authorized, long captured, long refunded, long voided) {
    Payment p = Payment.authorize(UUID.randomUUID(), "m", authorized, "USD", "psp", null, NOW);
    return new Payment(
        p.id(),
        "m",
        authorized,
        "USD",
        authorized,
        captured,
        refunded,
        voided,
        p.state(),
        "psp",
        null,
        0,
        NOW,
        NOW);
  }

  private static PaymentOperation op(
      Payment p, OperationType type, long amount, long fee, String pspStatus) {
    return new PaymentOperation(
        UUID.randomUUID(), p.id(), type, amount, fee, "SUCCEEDED", "ref", pspStatus, NOW);
  }

  private static PaymentEntry entry(
      Payment p, LedgerAccountType type, Direction direction, long amount) {
    return new PaymentEntry(p.id(), type, direction, amount);
  }

  @Test
  void aConsistentPaymentHasNoDiscrepancies() {
    Payment p = payment(1_000, 400, 100, 0);
    List<PaymentOperation> ops =
        List.of(
            op(p, OperationType.CAPTURE, 400, 10, "PENDING"),
            op(p, OperationType.REFUND, 100, 0, "CONFIRMED"));
    List<PaymentEntry> entries =
        List.of(
            entry(p, LedgerAccountType.PSP_CLEARING, Direction.DEBIT, 400),
            entry(p, LedgerAccountType.MERCHANT_PAYABLE, Direction.CREDIT, 390),
            entry(p, LedgerAccountType.FEE_REVENUE, Direction.CREDIT, 10),
            entry(p, LedgerAccountType.MERCHANT_RECEIVABLE, Direction.DEBIT, 100),
            entry(p, LedgerAccountType.REFUND_LIABILITY, Direction.CREDIT, 100));
    assertThat(service.check(RUN, p, ops, entries, NOW)).isEmpty();
  }

  @Test
  void aMissingLedgerPostingIsReportedPerAccount() {
    Payment p = payment(1_000, 400, 0, 0);
    List<PaymentOperation> ops = List.of(op(p, OperationType.CAPTURE, 400, 10, "PENDING"));
    List<Discrepancy> issues = service.check(RUN, p, ops, List.of(), NOW);
    assertThat(issues)
        .extracting(Discrepancy::subject)
        .containsExactlyInAnyOrder("PSP_CLEARING", "MERCHANT_PAYABLE", "FEE_REVENUE");
    assertThat(issues).allSatisfy(d -> assertThat(d.kind()).isEqualTo("LEDGER_POSITION_MISMATCH"));
  }

  @Test
  void countersThatDisagreeWithOperationsAreReported() {
    Payment p = payment(1_000, 500, 0, 300);
    List<Discrepancy> issues = service.check(RUN, p, List.of(), List.of(), NOW);
    assertThat(issues).extracting(Discrepancy::subject).contains("captured_minor", "voided_minor");
    assertThat(issues).extracting(Discrepancy::kind).contains("AGGREGATE_MISMATCH");
  }

  @Test
  void anOperationFailedAtThePspIsNotExpectedToBeInTheLedger() {
    Payment p = payment(1_000, 0, 0, 0);
    List<PaymentOperation> ops = List.of(op(p, OperationType.CAPTURE, 400, 10, "FAILED"));
    assertThat(service.check(RUN, p, ops, List.of(), NOW)).isEmpty();
  }

  @Test
  void operationsThatCannotBeSummedWithoutOverflowAreFlaggedNotCrashed() {
    Payment p = payment(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0);
    List<PaymentOperation> ops =
        List.of(
            op(p, OperationType.CAPTURE, Long.MAX_VALUE, 0, "PENDING"),
            op(p, OperationType.CAPTURE, Long.MAX_VALUE, 0, "PENDING"));
    List<Discrepancy> issues = service.check(RUN, p, ops, List.of(), NOW);
    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).subject()).isEqualTo("operations_unsummable");
  }

  @Test
  void ledgerEntriesThatCannotBeSummedWithoutOverflowAreFlaggedNotCrashed() {
    Payment p = payment(1_000, 0, 0, 0);
    List<PaymentEntry> entries =
        List.of(
            entry(p, LedgerAccountType.PSP_CLEARING, Direction.DEBIT, Long.MAX_VALUE),
            entry(p, LedgerAccountType.PSP_CLEARING, Direction.DEBIT, Long.MAX_VALUE));
    List<Discrepancy> issues = service.check(RUN, p, List.of(), entries, NOW);
    assertThat(issues).extracting(Discrepancy::subject).contains("entries_unsummable");
  }
}
