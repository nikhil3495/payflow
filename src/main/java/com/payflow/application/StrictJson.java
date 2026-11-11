package com.payflow.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Parses and validates request bodies. Parsing is strict: unknown fields, duplicate keys, quoted
 * numbers and fractional numbers for integer fields are rejected, so {@code "amount_minor": 10.5}
 * can never silently become 10. Failures are {@link ApiException}s so they can be stored against an
 * idempotency key like any other 4xx.
 */
@Component
public class StrictJson {

  private final ObjectMapper mapper;
  private final Validator validator;

  public StrictJson(Validator validator) {
    this.mapper =
        JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    this.validator = validator;
  }

  /** Parses a required body. An empty body is treated as absent fields, i.e. a validation error. */
  public <T> T parse(byte[] body, Class<T> type) {
    if (body == null || body.length == 0) {
      throw new ApiException(ErrorCode.MALFORMED_REQUEST, "A JSON request body is required.");
    }
    return validated(read(body, type));
  }

  /** Parses an optional body: an empty body yields {@code emptyValue}. */
  public <T> T parseOptional(byte[] body, Class<T> type, T emptyValue) {
    if (body == null || body.length == 0 || isBlank(body)) {
      return emptyValue;
    }
    return validated(read(body, type));
  }

  private <T> T read(byte[] body, Class<T> type) {
    try {
      T value = mapper.readValue(body, type);
      if (value == null) {
        throw new ApiException(
            ErrorCode.MALFORMED_REQUEST, "The request body must be a JSON object.");
      }
      return value;
    } catch (UnrecognizedPropertyException e) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          "Unknown field in request body.",
          List.of(new ApiException.FieldIssue(e.getPropertyName(), "unknown field")),
          null);
    } catch (MismatchedInputException e) {
      String field =
          e.getPath().isEmpty() ? "body" : e.getPath().get(e.getPath().size() - 1).getFieldName();
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          "A field has the wrong type or format.",
          List.of(
              new ApiException.FieldIssue(field == null ? "body" : field, "invalid type or value")),
          null);
    } catch (JsonProcessingException e) {
      throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The request body is not valid JSON.");
    } catch (IOException e) {
      throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The request body could not be read.");
    }
  }

  private <T> T validated(T value) {
    Set<ConstraintViolation<T>> violations = validator.validate(value);
    if (!violations.isEmpty()) {
      List<ApiException.FieldIssue> issues =
          violations.stream()
              .sorted(
                  Comparator.comparing(
                      (ConstraintViolation<T> v) -> v.getPropertyPath().toString()))
              .map(
                  v ->
                      new ApiException.FieldIssue(
                          toSnakeCase(v.getPropertyPath().toString()), v.getMessage()))
              .toList();
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR, "The request failed validation.", issues, null);
    }
    return value;
  }

  private static boolean isBlank(byte[] body) {
    for (byte b : body) {
      if (!Character.isWhitespace(b)) {
        return false;
      }
    }
    return true;
  }

  private static String toSnakeCase(String camel) {
    StringBuilder out = new StringBuilder(camel.length() + 4);
    for (char c : camel.toCharArray()) {
      if (Character.isUpperCase(c)) {
        out.append('_').append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
