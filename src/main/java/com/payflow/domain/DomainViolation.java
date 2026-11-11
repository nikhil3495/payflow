package com.payflow.domain;

public final class DomainViolation extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final DomainError error;

  public DomainViolation(DomainError error, String message) {
    super(message);
    this.error = error;
  }

  public DomainError error() {
    return error;
  }
}
