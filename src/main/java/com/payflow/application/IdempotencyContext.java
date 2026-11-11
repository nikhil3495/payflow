package com.payflow.application;

import java.util.Optional;

/**
 * The idempotency scope of the request being executed on this thread, so side effects outside the
 * database (the PSP call) can use a key that is stable across transaction retries. Operation ids
 * are regenerated on every attempt and therefore must not be used for that purpose.
 */
final class IdempotencyContext {

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private IdempotencyContext() {}

  static void set(String scope) {
    CURRENT.set(scope);
  }

  static void clear() {
    CURRENT.remove();
  }

  static Optional<String> current() {
    return Optional.ofNullable(CURRENT.get());
  }
}
