package com.innbucks.loyaltyservice.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Method-level @PreAuthorize throws AuthorizationDeniedException (a subclass
    // of AccessDeniedException in Spring Security 6). Without this handler it
    // falls through to the generic Exception handler below and returns 500
    // instead of 403, masking permission errors as server errors.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handle(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 403,
                "code", "FORBIDDEN",
                "message", "Forbidden - insufficient role or not the tenant owner"
        ));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handle(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 401,
                "code", "UNAUTHORIZED",
                "message", "Invalid or missing token"
        ));
    }

    @ExceptionHandler(LoyaltyException.class)
    public ResponseEntity<Map<String, Object>> handle(LoyaltyException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", ex.getStatus().value(),
                "code", ex.getCode(),
                "message", ex.getMessage()
        ));
    }

    /**
     * @Valid bean-validation failures on @RequestBody. Returns the project's
     * standard ApiResult envelope with field-level messages in data — same
     * shape as the matching handler in user-service / seat-service /
     * booking-service / event-service / payment-service so the frontend has
     * one render path for validation errors across the API.
     *
     * <p>Previously returned a raw Map with a top-level "fields" key — that
     * worked but differed from every other service. data: { field → msg }
     * is now the canonical shape.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<com.innbucks.loyaltyservice.dto.ApiResult<Map<String, String>>> handle(
            MethodArgumentNotValidException ex) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (var fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(),
                    fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage());
        }
        log.warn("Validation failed fields={}", fields);
        return ResponseEntity.badRequest().body(
                com.innbucks.loyaltyservice.dto.ApiResult.<Map<String, String>>builder()
                        .code("400 BAD_REQUEST")
                        .message("Validation failed")
                        .data(fields)
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handle(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 400,
                "code", "BAD_REQUEST",
                "message", ex.getMessage() == null ? "bad request" : ex.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex) {
        // Don't swallow the cause: a 500 with an opaque body is hard enough to
        // diagnose in prod without the stack trace also being missing from the
        // logs. The response stays generic so we don't leak internals; the log
        // is where on-call goes to find the real problem.
        log.error("Unhandled exception bubbled to GlobalExceptionHandler", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 500,
                "code", "INTERNAL",
                "message", "internal error"
        ));
    }
}
