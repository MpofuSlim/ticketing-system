package com.innbucks.userservice.exception;

import com.innbucks.userservice.client.OradianClientException;
import com.innbucks.userservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Let Spring Security's entry point / access-denied handler produce the 401/403
    // envelopes instead of being swallowed as a generic 400.
    @ExceptionHandler(AuthenticationException.class)
    public void handleAuthentication(AuthenticationException ex) throws AuthenticationException {
        throw ex;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException ex) throws AccessDeniedException {
        throw ex;
    }

    /**
     * @Valid bean-validation failures on @RequestBody. Surfaces field-level
     * messages in data.fields so the frontend can highlight the specific bad
     * input without parsing a free-form string. Without this handler the
     * request fell through to Spring's DefaultHandlerExceptionResolver which
     * returned a cryptic "Validation failed for object='customerTier2RegisterDTO'"
     * with no per-field detail — useless to the user.
     *
     * <p>{@code data} is a map keyed by field name, value is the
     * {@code @NotBlank(message=…)} / {@code @Size(message=…)} text from the
     * DTO. Multiple violations on the same field collapse to the first one
     * (showing all of them tends to confuse rather than help).
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

    // Surface Oradian middleware failures as 502 Bad Gateway so the client knows
    // the local state was rolled back — distinct from a 400 caused by user input.
    @ExceptionHandler(OradianClientException.class)
    public ResponseEntity<ApiResult<Void>> handleOradian(OradianClientException ex) {
        log.warn("Oradian middleware call failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResult.error(HttpStatus.BAD_GATEWAY, ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResult<Void>> handleRuntime(RuntimeException ex) {
        log.warn("RuntimeException: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }
}
