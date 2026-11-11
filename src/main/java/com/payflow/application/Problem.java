package com.payflow.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** RFC 9457 problem details body, plus a stable {@code code} extension member. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Problem(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    List<ApiException.FieldIssue> errors,
    Integer retryAfterSeconds) {

  public Problem {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public static final String MEDIA_TYPE = "application/problem+json";

  public static Problem of(ApiException e, String instance) {
    return new Problem(
        e.code().typeUri(),
        e.code().title(),
        e.code().status(),
        e.getMessage(),
        instance,
        e.code().name(),
        e.issues(),
        e.retryAfterSeconds());
  }
}
