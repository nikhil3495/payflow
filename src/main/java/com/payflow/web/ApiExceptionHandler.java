package com.payflow.web;

import com.payflow.application.ApiException;
import com.payflow.application.ErrorCode;
import com.payflow.application.Problem;
import com.payflow.domain.DomainViolation;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every failure into an RFC 9457 problem+json body. Stack traces never reach clients. */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Problem> api(ApiException e, HttpServletRequest request) {
    if (e.code().status() >= 500) {
      LOG.warn("request failed with {}: {}", e.code(), e.getMessage());
    }
    return respond(e, request);
  }

  @ExceptionHandler(DomainViolation.class)
  ResponseEntity<Problem> domain(DomainViolation e, HttpServletRequest request) {
    return respond(ApiException.from(e), request);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Problem> methodNotAllowed(
      HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
    return respond(new ApiException(ErrorCode.METHOD_NOT_ALLOWED, "Method not allowed."), request);
  }

  @ExceptionHandler({
    HttpMediaTypeNotSupportedException.class,
    HttpMediaTypeNotAcceptableException.class
  })
  ResponseEntity<Problem> mediaType(Exception e, HttpServletRequest request) {
    return respond(
        new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Use application/json."), request);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingRequestValueException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Problem> unreadable(Exception e, HttpServletRequest request) {
    return respond(
        new ApiException(ErrorCode.MALFORMED_REQUEST, "The request could not be parsed."), request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Problem> noResource(NoResourceFoundException e, HttpServletRequest request) {
    return respond(new ApiException(ErrorCode.NOT_FOUND, "No such resource."), request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Problem> unexpected(Exception e, HttpServletRequest request) {
    LOG.error("unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
    return respond(
        new ApiException(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred."), request);
  }

  private static ResponseEntity<Problem> respond(ApiException e, HttpServletRequest request) {
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(e.code().status())
            .contentType(MediaType.parseMediaType(Problem.MEDIA_TYPE));
    if (e.retryAfterSeconds() != null) {
      builder.header(HttpHeaders.RETRY_AFTER, Integer.toString(e.retryAfterSeconds()));
    }
    return builder.body(Problem.of(e, request.getRequestURI()));
  }
}
