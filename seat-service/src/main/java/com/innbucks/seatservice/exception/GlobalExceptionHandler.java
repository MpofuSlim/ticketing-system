package com.innbucks.seatservice.exception;

import com.innbucks.seatservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public void handleAuthentication(AuthenticationException ex) throws AuthenticationException {
        throw ex;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException ex) throws AccessDeniedException {
        throw ex;
    }

    /**
     * @Valid bean-validation failures on @RequestBody. Returns field-level
     * messages keyed by field name in data.fields — see the matching handler
     * in user-service / event-service / loyalty-service for the rationale
     * (one response shape across the API so the frontend has one render
     * path for validation errors).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (var fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(),
                    fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage());
        }
        log.warn("Validation failed fields={}", fields);
        return ResponseEntity.badRequest().body(ApiResult.<Map<String, String>>builder()
                .code("400 BAD_REQUEST")
                .message("Validation failed")
                .data(fields)
                .build());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResult<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResult.error(HttpStatus.CONFLICT,
                        "Seat was modified by another request. Please retry."));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNotFound(NotFoundException ex) {
        log.warn("NotFound: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResult<Void>> handleBadRequest(BadRequestException ex) {
        log.warn("BadRequest: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResult<Void>> handleConflict(ConflictException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResult.error(HttpStatus.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResult<Void>> handleForbidden(ForbiddenException ex) {
        log.warn("Forbidden: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResult.error(HttpStatus.FORBIDDEN, ex.getMessage()));
    }

    /**
     * Catch-all for any RuntimeException that did NOT match a typed handler
     * above. Pre-refactor this returned 400 with the raw exception message,
     * which (a) misclassified genuine server bugs as client errors, (b) sniffed
     * the message string for "not found" to upgrade to 404 — fragile across
     * wording changes, and (c) leaked internal exception detail to whoever
     * called the API. Now returns 500 with a generic message; the real cause
     * is logged at ERROR with a stack trace so operators can still debug.
     *
     * <p>If you're hitting this handler from a legitimate 4xx case, introduce
     * a typed exception ({@link NotFoundException}, {@link BadRequestException},
     * {@link ConflictException}, {@link ForbiddenException}) at the throw site
     * rather than widening this fallback.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResult<Void>> handleRuntime(RuntimeException ex) {
        log.error("Unhandled RuntimeException — returning 500 to client", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(HttpStatus.INTERNAL_SERVER_ERROR,
                        "An unexpected error occurred. Please try again."));
    }
}
