package com.payflow.web.dto;

import com.payflow.application.PaymentService.LedgerView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerResponse(UUID paymentId, List<Transaction> transactions) {

  public LedgerResponse {
    transactions = List.copyOf(transactions);
  }

  public record Transaction(
      UUID id, UUID operationId, String description, Instant createdAt, List<Entry> entries) {
    public Transaction {
      entries = List.copyOf(entries);
    }
  }

  public record Entry(UUID accountId, String accountType, String direction, long amountMinor) {}

  public static LedgerResponse from(LedgerView view) {
    return new LedgerResponse(
        view.payment().id(),
        view.transactions().stream()
            .map(
                tx ->
                    new Transaction(
                        tx.id(),
                        tx.operationId(),
                        tx.description(),
                        tx.createdAt(),
                        tx.entries().stream()
                            .map(
                                e ->
                                    new Entry(
                                        e.accountId(),
                                        e.accountType().name(),
                                        e.direction().name(),
                                        e.amountMinor()))
                            .toList()))
            .toList());
  }
}
