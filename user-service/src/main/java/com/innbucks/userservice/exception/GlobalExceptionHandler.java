package com.innbucks.userservice.exception;

import com.innbucks.userservice.client.OradianClientException;
import com.innbucks.userservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

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
