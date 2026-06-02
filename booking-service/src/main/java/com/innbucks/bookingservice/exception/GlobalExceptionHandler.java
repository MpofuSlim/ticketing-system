package com.innbucks.bookingservice.exception;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TierViolationData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
     * messages keyed by field name in data.fields — same shape as the matching
     * handler in user-service / seat-service / event-service / loyalty-service.
     * One response shape across the API = one render path on the frontend.
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

    @ExceptionHandler(SeatServiceUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> handleSeatServiceUnavailable(SeatServiceUnavailableException ex) {
        log.warn("seat-service unavailable: {}", ex.getMessage());
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

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNotFound(NotFoundException ex) {
        log.warn("NotFound: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(BookingConflictException.class)
    public ResponseEntity<ApiResult<Void>> handleBookingConflict(BookingConflictException ex) {
        log.warn("BookingConflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResult.error(HttpStatus.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResult<Void>> handleBadRequest(BadRequestException ex) {
        log.warn("BadRequest: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> handleDependencyUnavailable(DependencyUnavailableException ex) {
        log.warn("Dependency unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResult.error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    /**
     * Catch-all for any RuntimeException that did NOT match a typed handler
     * above. Audit item #9: previously this returned 400 with the exception's
     * raw message, which (a) misclassified genuine server bugs like a
     * DataIntegrityViolationException as client errors and (b) leaked
     * internal exception detail to whoever called the API. Now returns 500
     * with a generic message; the real cause is logged at ERROR with a stack
     * trace so operators can still debug, but the client never sees it.
     *
     * <p>If you're hitting this handler from a legitimate 4xx case, that's
     * the signal to introduce a typed exception ({@link BadRequestException},
     * {@link BookingConflictException}, {@link NotFoundException},
     * {@link DependencyUnavailableException}) at the throw site rather than
     * widening this fallback.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResult<Void>> handleRuntime(RuntimeException ex) {
        log.error("Unhandled RuntimeException — returning 500 to client", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(HttpStatus.INTERNAL_SERVER_ERROR,
                        "An unexpected error occurred. Please try again."));
    }
}
