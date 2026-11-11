package com.payflow.application;

import com.payflow.domain.DomainViolation;
import java.util.List;

/** A failure that maps to a specific HTTP status and problem+json body. */
public final class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public record FieldIssue(String field, String message) {}

  private final ErrorCode code;
  private final transient List<FieldIssue> issues;
  private final Integer retryAfterSeconds;

  public ApiException(ErrorCode code, String detail) {
    this(code, detail, List.of(), null);
  }

  public ApiException(
      ErrorCode code, String detail, List<FieldIssue> issues, Integer retryAfterSeconds) {
    super(detail);
    this.code = code;
    this.issues = List.copyOf(issues);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public static ApiException from(DomainViolation violation) {
    return new ApiException(ErrorCode.from(violation.error()), violation.getMessage());
  }

  public ErrorCode code() {
    return code;
  }

  public List<FieldIssue> issues() {
    return issues;
  }

  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }

  /** 4xx outcomes are deterministic answers to the request and are stored for idempotent replay. */
  public boolean isClientError() {
    return code.status() >= 400 && code.status() < 500;
  }
}
