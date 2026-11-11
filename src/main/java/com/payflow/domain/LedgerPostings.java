package com.payflow.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The accounting rules. Every posting is balanced by construction (and again by a database
 * trigger). Authorizations and voids move no money, so they post nothing.
 *
 * <pre>
 * capture A, fee F : DR PSP_CLEARING A | CR MERCHANT_PAYABLE A-F | CR FEE_REVENUE F
 * refund  R        : DR MERCHANT_RECEIVABLE R | CR REFUND_LIABILITY R
 * </pre>
 */
public final class LedgerPostings {

  public record EntryDraft(LedgerAccountType account, Direction direction, long amountMinor) {}

  private LedgerPostings() {}

  public static List<EntryDraft> forCapture(long amountMinor, long feeMinor) {
    Amounts.requirePositive(amountMinor);
    if (feeMinor < 0 || feeMinor > amountMinor) {
      throw new IllegalArgumentException("fee must be within [0, amount]");
    }
    var entries = new java.util.ArrayList<EntryDraft>(3);
    entries.add(new EntryDraft(LedgerAccountType.PSP_CLEARING, Direction.DEBIT, amountMinor));
    long net = amountMinor - feeMinor;
    if (net > 0) {
      entries.add(new EntryDraft(LedgerAccountType.MERCHANT_PAYABLE, Direction.CREDIT, net));
    }
    if (feeMinor > 0) {
      entries.add(new EntryDraft(LedgerAccountType.FEE_REVENUE, Direction.CREDIT, feeMinor));
    }
    return List.copyOf(entries);
  }

  public static List<EntryDraft> forRefund(long amountMinor) {
    Amounts.requirePositive(amountMinor);
    return List.of(
        new EntryDraft(LedgerAccountType.MERCHANT_RECEIVABLE, Direction.DEBIT, amountMinor),
        new EntryDraft(LedgerAccountType.REFUND_LIABILITY, Direction.CREDIT, amountMinor));
  }

  /**
   * Net debit position (debits minus credits) per account type; used by tests and reconciliation.
   */
  public static Map<LedgerAccountType, Long> netDebitByAccount(List<EntryDraft> entries) {
    Map<LedgerAccountType, Long> position = new EnumMap<>(LedgerAccountType.class);
    for (EntryDraft entry : entries) {
      long signed =
          entry.direction() == Direction.DEBIT ? entry.amountMinor() : -entry.amountMinor();
      position.merge(entry.account(), signed, Amounts::add);
    }
    return position;
  }

  public static boolean isBalanced(List<EntryDraft> entries) {
    long debits = 0;
    long credits = 0;
    for (EntryDraft entry : entries) {
      if (entry.direction() == Direction.DEBIT) {
        debits = Amounts.add(debits, entry.amountMinor());
      } else {
        credits = Amounts.add(credits, entry.amountMinor());
      }
    }
    return debits == credits;
  }
}
