package com.payflow.domain;

public enum LedgerAccountType {
  /** Money the PSP owes the platform. Platform-wide. */
  PSP_CLEARING(false),
  /** Money a merchant owes the platform (refunds paid out). */
  MERCHANT_RECEIVABLE(true),
  /** Money the platform owes a merchant (captures net of fees). */
  MERCHANT_PAYABLE(true),
  /** Platform fee income. Platform-wide. */
  FEE_REVENUE(false),
  /** Money owed back to cardholders. */
  REFUND_LIABILITY(true);

  private final boolean merchantScoped;

  LedgerAccountType(boolean merchantScoped) {
    this.merchantScoped = merchantScoped;
  }

  public boolean merchantScoped() {
    return merchantScoped;
  }
}
