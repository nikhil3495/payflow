package com.payflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.domain.LedgerPostings.EntryDraft;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerPostingsTest {

  @Test
  void captureSplitsIntoNetPayableAndFeeAndBalances() {
    List<EntryDraft> entries = LedgerPostings.forCapture(10_000, 250);
    assertThat(entries)
        .containsExactly(
            new EntryDraft(LedgerAccountType.PSP_CLEARING, Direction.DEBIT, 10_000),
            new EntryDraft(LedgerAccountType.MERCHANT_PAYABLE, Direction.CREDIT, 9_750),
            new EntryDraft(LedgerAccountType.FEE_REVENUE, Direction.CREDIT, 250));
    assertThat(LedgerPostings.isBalanced(entries)).isTrue();
  }

  @Test
  void zeroFeeOmitsTheFeeLineAndStillBalances() {
    List<EntryDraft> entries = LedgerPostings.forCapture(10, 0);
    assertThat(entries).hasSize(2);
    assertThat(LedgerPostings.isBalanced(entries)).isTrue();
  }

  @Test
  void feeEqualToAmountOmitsThePayableLine() {
    List<EntryDraft> entries = LedgerPostings.forCapture(10, 10);
    assertThat(entries).hasSize(2);
    assertThat(LedgerPostings.isBalanced(entries)).isTrue();
  }

  @Test
  void maximumAmountBalancesWithoutOverflow() {
    List<EntryDraft> entries =
        LedgerPostings.forCapture(Long.MAX_VALUE, Amounts.feeFor(Long.MAX_VALUE, 250));
    assertThat(LedgerPostings.isBalanced(entries)).isTrue();
    assertThat(LedgerPostings.netDebitByAccount(entries).get(LedgerAccountType.PSP_CLEARING))
        .isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void refundMovesReceivableAgainstRefundLiability() {
    List<EntryDraft> entries = LedgerPostings.forRefund(400);
    assertThat(entries)
        .containsExactly(
            new EntryDraft(LedgerAccountType.MERCHANT_RECEIVABLE, Direction.DEBIT, 400),
            new EntryDraft(LedgerAccountType.REFUND_LIABILITY, Direction.CREDIT, 400));
    assertThat(LedgerPostings.isBalanced(entries)).isTrue();
  }

  @Test
  void netPositionIsDebitsMinusCredits() {
    var position = LedgerPostings.netDebitByAccount(LedgerPostings.forCapture(1_000, 25));
    assertThat(position)
        .containsEntry(LedgerAccountType.PSP_CLEARING, 1_000L)
        .containsEntry(LedgerAccountType.MERCHANT_PAYABLE, -975L)
        .containsEntry(LedgerAccountType.FEE_REVENUE, -25L);
  }

  @Test
  void invalidInputsAreRejected() {
    assertThatThrownBy(() -> LedgerPostings.forCapture(0, 0)).isInstanceOf(DomainViolation.class);
    assertThatThrownBy(() -> LedgerPostings.forCapture(10, 11))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LedgerPostings.forCapture(10, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LedgerPostings.forRefund(-5)).isInstanceOf(DomainViolation.class);
  }

  @Test
  void unbalancedDraftsAreDetected() {
    assertThat(
            LedgerPostings.isBalanced(
                List.of(new EntryDraft(LedgerAccountType.PSP_CLEARING, Direction.DEBIT, 5))))
        .isFalse();
  }
}
