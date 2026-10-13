package com.innbucks.bookingservice.exception;

import com.innbucks.bookingservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** {@code data.errorCode} of the global optimistic-lock 409. */
    public static final String CONCURRENT_UPDATE = "CONCURRENT_UPDATE";

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

    /**
     * A body Jackson could not bind: a malformed id ({@code "seats[0].categoryId":
     * "c-vip"}), a word where a number goes, broken JSON, no body at all. These
     * extend RuntimeException, so they used to reach the catch-all below and
     * answer 500 for what is the caller's mistake. Same shape as bean
     * validation, keyed by the Jackson path ({@code seats[0].categoryId}), so a
     * client renders both with one code path.
     *
     * <p>Neither the body nor the exception message is logged or returned:
     * Jackson quotes the offending input in its message, and a request body can
     * carry a phone number or a name. The path and the exception type are
     * enough to debug.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handleUnreadable(HttpMessageNotReadableException ex) {
        Map<String, String> fields = unreadableFields(ex);
        log.warn("Unreadable request body fields={} cause={}", fields.keySet(),
                ex.getMostSpecificCause().getClass().getSimpleName());
        return validationFailed(fields);
    }

    /**
     * A path variable or query parameter that cannot convert to its type —
     * {@code PATCH /bookings/not-a-uuid/cancel}. A 500 before this handler.
     * Keyed by the parameter name; the rejected value is not echoed.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ex.getName(), expectation(ex.getRequiredType()));
        log.warn("Parameter type mismatch param={} requiredType={}", ex.getName(),
                ex.getRequiredType() == null ? null : ex.getRequiredType().getSimpleName());
        return validationFailed(fields);
    }

    /**
     * A write that lost an optimistic-lock race (a booking's {@code @Version}
     * moved under it): 409, not the catch-all's 500. Confirm used to be the
     * only path that caught it, in its controller; a reversal racing another
     * change and an extend-hold racing the expiry sweep were 500s. The request
     * is safe to retry after re-reading the booking.
     *
     * <p>Spring translates the failure when it surfaces through a repository
     * or at commit; the raw JPA exception is listed too because a flush
     * Hibernate triggers by itself (an auto-flush before an unrelated query)
     * reaches the controller untranslated. Confirm's own catch covered both;
     * this handler replaces it.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<ApiResult<Map<String, Object>>> handleOptimisticLock(RuntimeException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getClass().getSimpleName());
        return problem(new ApiProblemException(HttpStatus.CONFLICT, CONCURRENT_UPDATE,
                "This booking was changed by another request at the same time. Please refresh it and try again."));
    }

    /** A refusal with a domain code — see {@link ApiProblemException}. */
    @ExceptionHandler(ApiProblemException.class)
    public ResponseEntity<ApiResult<Map<String, Object>>> handleApiProblem(ApiProblemException ex) {
        log.warn("ApiProblem status={} errorCode={}", ex.getStatus().value(), ex.getErrorCode());
        return problem(ex);
    }

    private static ResponseEntity<ApiResult<Map<String, Object>>> problem(ApiProblemException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResult.of(ex.getStatus(), ex.getMessage(), ex.data()));
    }

    private static ResponseEntity<ApiResult<Map<String, String>>> validationFailed(Map<String, String> fields) {
        return ResponseEntity.badRequest().body(ApiResult.<Map<String, String>>builder()
                .code("400 BAD_REQUEST")
                .message("Validation failed")
                .data(fields)
                .build());
    }

    /**
     * {@code {"<jackson path>": "<what it must be>"}} for a body Jackson
     * rejected, or {@code {"body": …}} when the failure has no field: broken
     * JSON, well-formed JSON of the wrong kind ({@code []}, a bare string or
     * number, a second value after the object), or no body at all.
     */
    private static Map<String, String> unreadableFields(HttpMessageNotReadableException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Throwable t = ex.getCause(); t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof tools.jackson.core.JacksonException jackson) {
                String path = jacksonPath(jackson.getPath().stream()
                        .map(r -> new PathStep(r.getPropertyName(), r.getIndex())).toList());
                if (!path.isEmpty()) {
                    fields.put(path, describe(t));
                    return fields;
                }
                if (t instanceof tools.jackson.databind.exc.MismatchedInputException mismatch) {
                    fields.put("body", wrongShape(mismatch.getTargetType()));
                    return fields;
                }
                break;
            }
            if (t instanceof com.fasterxml.jackson.databind.JsonMappingException jackson) {
                String path = jacksonPath(jackson.getPath().stream()
                        .map(r -> new PathStep(r.getFieldName(), r.getIndex())).toList());
                if (!path.isEmpty()) {
                    fields.put(path, describe(t));
                    return fields;
                }
                if (t instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException mismatch) {
                    fields.put("body", wrongShape(mismatch.getTargetType()));
                    return fields;
                }
                break;
            }
        }
        fields.put("body", ex.getCause() == null
                ? "A request body is required."
                : "The request body is not valid JSON.");
        return fields;
    }

    /**
     * The body parsed, but as the wrong kind of value: a mismatch with no
     * field path is about the whole body. Telling that client its JSON is
     * invalid sends them hunting for a syntax error that is not there.
     * "Single" also covers a second value after the object, which Jackson 3
     * refuses the same way (FAIL_ON_TRAILING_TOKENS).
     */
    private static String wrongShape(Class<?> rootType) {
        return rootType != null && (Collection.class.isAssignableFrom(rootType) || rootType.isArray())
                ? "The request body must be a single JSON array."
                : "The request body must be a single JSON object.";
    }

    private record PathStep(String property, int index) {
    }

    /** {@code seats[0].categoryId} — dotted properties, bracketed indexes. */
    private static String jacksonPath(List<PathStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (PathStep step : steps) {
            if (step.property() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(step.property());
            } else if (step.index() >= 0) {
                sb.append('[').append(step.index()).append(']');
            }
        }
        return sb.toString();
    }

    private static String describe(Throwable jackson) {
        if (jackson instanceof tools.jackson.databind.exc.UnrecognizedPropertyException
                || jackson instanceof com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException) {
            return "Unknown field.";
        }
        if (jackson instanceof tools.jackson.databind.exc.MismatchedInputException mismatch) {
            return expectation(mismatch.getTargetType());
        }
        if (jackson instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException mismatch) {
            return expectation(mismatch.getTargetType());
        }
        return "Invalid value.";
    }

    /** What a value of {@code type} must look like, written for a person. Never echoes the input. */
    private static String expectation(Class<?> type) {
        if (type == null) {
            return "Invalid value.";
        }
        if (UUID.class.equals(type)) {
            return "Must be a valid UUID.";
        }
        if (type.isEnum()) {
            return "Must be one of: " + Arrays.stream(type.getEnumConstants())
                    .map(c -> ((Enum<?>) c).name()).collect(Collectors.joining(", ")) + ".";
        }
        if (Number.class.isAssignableFrom(type) || (type.isPrimitive() && type != boolean.class && type != char.class)) {
            return "Must be a number.";
        }
        if (Boolean.class.equals(type) || boolean.class.equals(type)) {
            return "Must be true or false.";
        }
        if (LocalDate.class.equals(type)) {
            return "Must be a date (yyyy-MM-dd).";
        }
        if (Temporal.class.isAssignableFrom(type)) {
            return "Must be an ISO-8601 date-time.";
        }
        if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            return "Must be a list.";
        }
        if (CharSequence.class.isAssignableFrom(type)) {
            return "Must be a string.";
        }
        return "Invalid value.";
    }

    @ExceptionHandler(SeatServiceUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> handleSeatServiceUnavailable(SeatServiceUnavailableException ex) {
        log.warn("seat-service unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResult.error(HttpStatus.SERVICE_UNAVAILABLE,
                        "We're having trouble checking seat availability right now. Please try again in a moment."));
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
                .body(ApiResult.error(HttpStatus.SERVICE_UNAVAILABLE,
                        "We're having trouble reaching part of the system right now. Please try again in a moment."));
    }

    /**
     * Spring's built-in {@link ResponseStatusException} extends RuntimeException,
     * so without this handler it would be swallowed by the {@code RuntimeException}
     * catch-all below and surface as a sanitised 500 — turning every deliberate
     * 4xx throw into "An unexpected error occurred." Honour the embedded status
     * and reason instead. Prefer the typed {@link NotFoundException} /
     * {@link BadRequestException} / etc. at the throw site; this handler is a
     * defence-in-depth net for code (Spring's own, libraries, lingering legacy
     * throws) that still uses ResponseStatusException directly.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResult<Void>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
        log.warn("ResponseStatusException status={} reason={}", status.value(), reason);
        return ResponseEntity.status(status).body(ApiResult.error(status, reason));
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
