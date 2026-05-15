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

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResult<Void>> handleRuntime(RuntimeException ex) {
        log.warn("RuntimeException: {}", ex.getMessage());
        HttpStatus status = ex.getMessage() != null && ex.getMessage().toLowerCase().contains("not found")
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(ApiResult.error(status, ex.getMessage()));
    }
}
