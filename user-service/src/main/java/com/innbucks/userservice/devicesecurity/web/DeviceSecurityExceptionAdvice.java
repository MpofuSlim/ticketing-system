package com.innbucks.userservice.devicesecurity.web;

import com.innbucks.userservice.devicesecurity.DeviceSecurityException;
import com.innbucks.userservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Renders device-security refusals as the standard envelope. Scoped to this
 * package's controllers and ordered ahead of {@code GlobalExceptionHandler} so a
 * {@link DeviceSecurityException} (a RuntimeException) is never demoted to the
 * catch-all's generic 400, and an unreadable body gets a message a developer can
 * act on without changing that behaviour for the rest of the service.
 */
@RestControllerAdvice(basePackageClasses = DeviceSecurityExceptionAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class DeviceSecurityExceptionAdvice {

    @ExceptionHandler(DeviceSecurityException.class)
    public ResponseEntity<ApiResult<Map<String, Object>>> handle(DeviceSecurityException ex) {
        log.info("Device-security refusal status={} errorCode={}", ex.getStatus().value(), ex.getErrorCode());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return builder.body(ApiResult.of(ex.getStatus(), ex.getMessage(), ex.getData()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Map<String, Object>>> unreadable(HttpMessageNotReadableException ex) {
        log.info("Device-security request body unreadable: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.badRequest().body(ApiResult.of(HttpStatus.BAD_REQUEST,
                "The request body isn't valid JSON, or a field has the wrong type.",
                Map.of("errorCode", "invalid_request")));
    }

    /**
     * Two requests for the same phone racing (an optimistic-lock loser), or a database
     * blip. Nothing definitive happened, so it must read as "try again shortly" (§7.7) —
     * never as the catch-all's 400, which the app would take for a malformed request.
     */
    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
    public ResponseEntity<ApiResult<Map<String, Object>>> transientFailure(RuntimeException ex) {
        log.warn("Device-security request hit a transient data error: {}", ex.getClass().getSimpleName());
        DeviceSecurityException e = DeviceSecurityException.tryAgainShortly();
        return ResponseEntity.status(e.getStatus()).body(ApiResult.of(e.getStatus(), e.getMessage(), e.getData()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResult<Map<String, Object>>> missingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(ApiResult.of(HttpStatus.BAD_REQUEST,
                "The " + ex.getHeaderName() + " header is required.",
                Map.of("errorCode", "invalid_request", "field", ex.getHeaderName())));
    }
}
