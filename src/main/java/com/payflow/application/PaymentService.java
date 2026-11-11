package com.payflow.application;

import com.payflow.config.PayflowProperties;
import com.payflow.domain.Amounts;
import com.payflow.domain.LedgerPostings;
import com.payflow.domain.LedgerTransactionView;
import com.payflow.domain.OperationType;
import com.payflow.domain.Payment;
import com.payflow.domain.PaymentOperation;
import com.payflow.infrastructure.LedgerRepository;
import com.payflow.infrastructure.OperationRepository;
import com.payflow.infrastructure.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment use cases. The money-moving methods ({@code authorize}, {@code capture}, {@code refund},
 * {@code voidPayment}) must be called inside the serializable transaction opened by {@link
 * IdempotentExecutor}; they refuse to run otherwise, so a refactor cannot silently drop the
 * transaction (or the row lock) they depend on.
 */
@Service
public class PaymentService {

  public record AuthorizeCommand(
      long amountMinor, String currency, String paymentMethodToken, String reference) {}

  public record MoneyCommand(long amountMinor, String currency) {}

  public record Outcome(Payment payment, PaymentOperation operation) {}

  public record LedgerView(Payment payment, List<LedgerTransactionView> transactions) {
    public LedgerView {
      transactions = List.copyOf(transactions);
    }
  }

  private final PaymentRepository payments;
  private final WebhookOutbox outbox;
  private final OperationRepository operations;
  private final LedgerRepository ledger;
  private final PspGateway psp;
  private final IdGenerator ids;
  private final Clock clock;
  private final PayflowProperties properties;
  private final TransactionTemplate readTx;

  public PaymentService(
      PaymentRepository payments,
      OperationRepository operations,
      LedgerRepository ledger,
      PspGateway psp,
      WebhookOutbox outbox,
      IdGenerator ids,
      Clock clock,
      PayflowProperties properties,
      PlatformTransactionManager transactionManager) {
    this.payments = payments;
    this.operations = operations;
    this.ledger = ledger;
    this.psp = psp;
    this.outbox = outbox;
    this.ids = ids;
    this.clock = clock;
    this.properties = properties;
    this.readTx = new TransactionTemplate(transactionManager);
    this.readTx.setReadOnly(true);
    this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  public Outcome authorize(String merchantId, AuthorizeCommand command) {
    requireTransaction();
    requireSupported(command.currency());
    Amounts.requirePositive(command.amountMinor());
    Instant now = clock.instant();
    UUID paymentId = ids.next();
    UUID operationId = ids.next();

    String pspReference;
    try {
      pspReference =
          psp.authorize(
              pspKey(OperationType.AUTHORIZE, operationId),
              command.paymentMethodToken(),
              command.amountMinor(),
              command.currency());
    } catch (PspGateway.DeclinedException e) {
      throw new ApiException(ErrorCode.PAYMENT_DECLINED, e.getMessage());
    } catch (PspGateway.UnavailableException e) {
      throw new ApiException(
          ErrorCode.PSP_UNAVAILABLE,
          e.getMessage(),
          List.of(),
          IdempotentExecutor.RETRY_AFTER_SECONDS);
    }

    Payment payment =
        Payment.authorize(
            paymentId,
            merchantId,
            command.amountMinor(),
            command.currency(),
            pspReference,
            command.reference(),
            now);
    payments.insert(payment);
    PaymentOperation operation =
        PaymentOperation.succeeded(
            operationId,
            paymentId,
            OperationType.AUTHORIZE,
            command.amountMinor(),
            0,
            pspReference,
            now);
    operations.insert(operation);
    outbox.enqueue(WebhookOutbox.AUTHORIZED, payment, operationId);
    return new Outcome(payment, operation);
  }

  public Outcome capture(String merchantId, String rawPaymentId, MoneyCommand command) {
    requireTransaction();
    requireSupported(command.currency());
    Payment locked = lock(merchantId, rawPaymentId);
    Instant now = clock.instant();
    Payment updated = locked.capture(command.amountMinor(), command.currency(), now);

    UUID operationId = ids.next();
    String reference =
        psp.capture(
            pspKey(OperationType.CAPTURE, operationId),
            locked.pspReference(),
            command.amountMinor());
    long fee = Amounts.feeFor(command.amountMinor(), properties.feeBps());
    PaymentOperation operation =
        PaymentOperation.succeeded(
            operationId,
            locked.id(),
            OperationType.CAPTURE,
            command.amountMinor(),
            fee,
            reference,
            now);

    Payment saved = payments.update(updated);
    operations.insert(operation);
    ledger.post(
        ids.next(),
        locked.id(),
        operationId,
        "capture " + command.amountMinor(),
        merchantId,
        locked.currency(),
        LedgerPostings.forCapture(command.amountMinor(), fee),
        now);
    outbox.enqueue(WebhookOutbox.CAPTURED, saved, operationId);
    return new Outcome(saved, operation);
  }

  public Outcome refund(String merchantId, String rawPaymentId, MoneyCommand command) {
    requireTransaction();
    requireSupported(command.currency());
    Payment locked = lock(merchantId, rawPaymentId);
    Instant now = clock.instant();
    Payment updated = locked.refund(command.amountMinor(), command.currency(), now);

    UUID operationId = ids.next();
    String reference =
        psp.refund(
            pspKey(OperationType.REFUND, operationId),
            locked.pspReference(),
            command.amountMinor());
    PaymentOperation operation =
        PaymentOperation.succeeded(
            operationId,
            locked.id(),
            OperationType.REFUND,
            command.amountMinor(),
            0,
            reference,
            now);

    Payment saved = payments.update(updated);
    operations.insert(operation);
    ledger.post(
        ids.next(),
        locked.id(),
        operationId,
        "refund " + command.amountMinor(),
        merchantId,
        locked.currency(),
        LedgerPostings.forRefund(command.amountMinor()),
        now);
    outbox.enqueue(WebhookOutbox.REFUNDED, saved, operationId);
    return new Outcome(saved, operation);
  }

  /** Releases the uncaptured remainder. Moves no money, so it posts no ledger entries. */
  public Outcome voidPayment(String merchantId, String rawPaymentId) {
    requireTransaction();
    Payment locked = lock(merchantId, rawPaymentId);
    Instant now = clock.instant();
    Payment updated = locked.voidRemainder(now);
    long released = updated.voidedMinor() - locked.voidedMinor();

    UUID operationId = ids.next();
    String reference =
        psp.voidAuthorization(pspKey(OperationType.VOID, operationId), locked.pspReference());
    PaymentOperation operation =
        PaymentOperation.succeeded(
            operationId, locked.id(), OperationType.VOID, released, 0, reference, now);

    Payment saved = payments.update(updated);
    operations.insert(operation);
    outbox.enqueue(WebhookOutbox.VOIDED, saved, operationId);
    return new Outcome(saved, operation);
  }

  public Payment get(String merchantId, String rawPaymentId) {
    UUID id = parseId(rawPaymentId);
    return payments
        .find(id, merchantId)
        .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "No such payment."));
  }

  public LedgerView ledger(String merchantId, String rawPaymentId) {
    UUID id = parseId(rawPaymentId);
    return readTx.execute(
        status -> {
          Payment payment =
              payments
                  .find(id, merchantId)
                  .orElseThrow(
                      () -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "No such payment."));
          return new LedgerView(payment, ledger.findByPayment(id));
        });
  }

  /** Locks the payment row for the rest of the transaction; all balance decisions follow. */
  private Payment lock(String merchantId, String rawPaymentId) {
    UUID id = parseId(rawPaymentId);
    return payments
        .lockForUpdate(id, merchantId)
        .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "No such payment."));
  }

  /**
   * The key the PSP deduplicates on. Within an idempotent request it is derived from the client's
   * Idempotency-Key, so a transaction that is retried after a serialization failure repeats the
   * same PSP call instead of creating a second hold or capture. Outside one it falls back to the
   * (unique) operation id.
   */
  private static String pspKey(OperationType type, UUID operationId) {
    return IdempotencyContext.current()
        .map(scope -> RequestFingerprint.sha256Hex(scope + ":" + type))
        .orElseGet(operationId::toString);
  }

  static UUID parseId(String raw) {
    try {
      UUID id = UUID.fromString(raw);
      if (!id.toString().equalsIgnoreCase(raw)) {
        throw new IllegalArgumentException("non-canonical uuid");
      }
      return id;
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_PAYMENT_ID, "The payment id is not a valid UUID.");
    }
  }

  private void requireSupported(String currency) {
    if (!properties.supportedCurrencies().contains(currency)) {
      throw new ApiException(
          ErrorCode.UNSUPPORTED_CURRENCY, "Currency " + currency + " is not supported.");
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("money-moving operations require a read-write transaction");
    }
  }
}
