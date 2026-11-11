package com.payflow.infrastructure;

/**
 * Raised when a statement lost a race that a fresh transaction snapshot will resolve (for example a
 * unique-key conflict with a row committed after our snapshot). The retry wrapper re-runs the whole
 * transaction.
 */
public final class TransientConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TransientConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
