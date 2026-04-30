package com.innbucks.bookingservice.exception;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TierViolationData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(SeatServiceUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> handleSeatServiceUnavailable(SeatServiceUnavailableException ex) {
        log.warn("seat-service unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResult.error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    @ExceptionHandler(UserServiceUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> handleUserServiceUnavailable(UserServiceUnavailableException ex) {
        log.warn("user-service unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResult.error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    @ExceptionHandler(TierRequirementException.class)
    public ResponseEntity<ApiResult<TierViolationData>> handleTierRequirement(TierRequirementException ex) {
        log.warn("Tier requirement not met requiredTier={} currentTier={} reason={}",
                ex.getRequiredTier(), ex.getCurrentTier(), ex.getMessage());
        ApiResult<TierViolationData> body = ApiResult.<TierViolationData>builder()
                .code(String.valueOf(HttpStatus.UNPROCESSABLE_ENTITY.value()))
                .message(ex.getMessage())
                .data(TierViolationData.builder()
                        .requiredTier(ex.getRequiredTier())
                        .currentTier(ex.getCurrentTier())
                        .build())
                .build();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResult<Void>> handleRuntime(RuntimeException ex) {
        log.warn("RuntimeException: {}", ex.getMessage());
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        HttpStatus status;
        if (msg.contains("not found")) {
            status = HttpStatus.NOT_FOUND;
        } else if (msg.contains("access denied")) {
            status = HttpStatus.FORBIDDEN;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .body(ApiResult.error(status, ex.getMessage()));
    }
}
