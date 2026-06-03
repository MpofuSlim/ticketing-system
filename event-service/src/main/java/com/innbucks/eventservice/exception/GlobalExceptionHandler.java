package com.innbucks.eventservice.exception;

import com.innbucks.eventservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Let Spring Security's entry point / access-denied handler deal with auth failures;
    // rethrow so they aren't downgraded to 400 by the generic RuntimeException handler below.
    @ExceptionHandler(AuthenticationException.class)
    public void handleAuthentication(AuthenticationException ex) throws AuthenticationException {
        throw ex;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException ex) throws AccessDeniedException {
        throw ex;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResult<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResult.error(HttpStatus.CONFLICT,
                        "Event was modified by another request. Please refetch and retry."));
    }

    // Banner / multipart payload exceeded the configured size. Returns 413 with
    // the actual byte limit so the client can show a real error and retry with
    // a smaller image instead of seeing a generic "Failed to parse multipart".
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResult<Void>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("Multipart payload too large: maxBytes={}", ex.getMaxUploadSize());
        long maxMb = Math.max(1, ex.getMaxUploadSize() / (1024 * 1024));
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResult.error(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Banner image is too large. Maximum allowed size is " + maxMb + " MB."));
    }

    // Catches every other multipart parse failure (truncated upload, broken
    // boundary, client disconnect mid-stream). Surfaces as 400 with a clear
    // message instead of falling into the generic RuntimeException handler.
    // Logs the full cause chain so the underlying reason (boundary mismatch,
    // EOF, malformed Content-Type) is visible in server logs — the wrapper
    // message alone is "Failed to parse multipart servlet request" which is
    // useless for diagnosis.
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResult<Void>> handleMultipart(MultipartException ex) {
        StringBuilder chain = new StringBuilder(ex.getClass().getSimpleName())
                .append(": ").append(ex.getMessage());
        Throwable cause = ex.getCause();
        while (cause != null) {
            chain.append(" -> ").append(cause.getClass().getSimpleName())
                 .append(": ").append(cause.getMessage());
            cause = cause.getCause();
        }
        log.warn("Multipart parse failed [{}]", chain);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(HttpStatus.BAD_REQUEST,
                        "Could not read the uploaded file. Please retry the upload."));
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
     * which (a) misclassified genuine server bugs (DataIntegrityViolationException,
     * NullPointerException, …) as client errors, (b) sniffed the message string
     * for "not found" to upgrade to 404 — fragile across wording changes, and
     * (c) leaked internal exception detail to whoever called the API. Now
     * returns 500 with a generic message; the real cause is logged at ERROR
     * with a stack trace so operators can still debug, but the client never
     * sees it.
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

    /**
     * @Valid bean-validation failures on @RequestBody. Field-level messages
     * land in data so the frontend can highlight the specific bad input.
     * Aligned to 400 BAD_REQUEST + ApiResult envelope across the API; the
     * previous 422 response was technically more REST-correct but forced the
     * frontend to special-case event-service.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                        (a, b) -> a,
                        java.util.LinkedHashMap::new
                ));
        log.warn("Validation failed fields={}", fieldErrors);
        return ResponseEntity.badRequest().body(ApiResult.<Map<String, String>>builder()
                .code("400 BAD_REQUEST")
                .message("Validation failed")
                .data(fieldErrors)
                .build());
    }
}
