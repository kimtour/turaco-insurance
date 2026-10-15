package com.sam.insurance;

import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

// Adapted from kimtour/insurance-policy-api GlobalExceptionHandler; see ADR-001.
@RestControllerAdvice
public class GlobalExceptionHandler {
  private ResponseEntity<Map<String, Object>> error(
      HttpStatusCode status, String code, Object detail) {
    return ResponseEntity.status(status)
        .body(
            Map.of("code", code, "detail", detail, "correlation_id", CorrelationFilter.current()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
    Map<String, String> fields = new TreeMap<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", fields);
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> business(ResponseStatusException ex) {
    return error(
        ex.getStatusCode(),
        "WORKFLOW_REJECTED",
        Objects.requireNonNullElse(ex.getReason(), "Request rejected"));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    jakarta.validation.ConstraintViolationException.class,
    tools.jackson.core.JacksonException.class,
    org.springframework.web.method.annotation.HandlerMethodValidationException.class
  })
  ResponseEntity<Map<String, Object>> malformed(Exception ex) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request values");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Map<String, Object>> conflict(Exception ex) {
    return error(
        HttpStatus.CONFLICT, "CONSTRAINT_CONFLICT", "Request conflicts with existing data");
  }

  @ExceptionHandler(EmptyResultDataAccessException.class)
  ResponseEntity<Map<String, Object>> missing(Exception ex) {
    return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
  }
}
