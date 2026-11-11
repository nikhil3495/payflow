package com.payflow.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransactionView(
    UUID id,
    UUID operationId,
    String description,
    Instant createdAt,
    List<LedgerEntryView> entries) {

  public LedgerTransactionView {
    entries = List.copyOf(entries);
  }

  public record LedgerEntryView(
      UUID accountId, LedgerAccountType accountType, Direction direction, long amountMinor) {}
}
