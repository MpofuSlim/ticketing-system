package innbucks.paymentservice.exception;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Funnels every exception escaping a controller into the project's standard
 * {@link ApiResult} envelope so clients see one consistent shape across the
 * whole service. Without this, Spring Boot's fallback {@code /error} handler
 * answers with its own JSON ({@code timestamp/status/error/trace/message})
 * AND — when devtools is on the classpath — ships the full stack trace to the
 * client. That's both an inconsistent contract and a security leak in prod.
 *
 * <p>Stack traces are logged here at the right severity; never returned in
 * the response body. {@code server.error.include-stacktrace=never} (set in
 * application.yaml) is the defence-in-depth backstop.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * @Valid binding failures on @RequestBody. Surfaces field-level messages
     * in {@code data.fields} so clients can highlight the specific bad input
     * without parsing a free-form string.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handle(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (var f : ex.getBindingResult().getFieldErrors()) {
            // Keep the first message per field — bean validation can stack
            // multiple violations per field (e.g. @NotNull + @PositiveOrZero)
            // and showing all of them tends to confuse rather than help.
            fields.putIfAbsent(f.getField(),
                    f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(ApiResult.<Map<String, String>>builder()
                .code("400 BAD_REQUEST")
                .message("validation failed")
                .data(fields)
                .build());
    }

    /**
     * Deliberate 4xx rejections from the service / controller layer (velocity
     * caps, amount parsing, cross-field rules between paymentMethod and
     * cash/points). Replaced the previous {@code @ExceptionHandler(IllegalArgumentException)}:
     * that one returned 400 with the raw exception message and ALSO caught
     * accidental JDK IAEs (e.g. {@code Map.of} with a null value), leaking
     * their internal text to the client. The typed exception keeps only the
     * intentional 4xx messages — accidental IAEs now fall to the sanitised
     * 500 catch-all below.
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResult<Void>> handle(BadRequestException ex) {
        return ResponseEntity.badRequest().body(ApiResult.<Void>builder()
                .code("400 BAD_REQUEST")
                .message(ex.getMessage() == null ? "bad request" : ex.getMessage())
                .data(null)
                .build());
    }

    // No AccessDenied / Authentication handlers here: payment-service runs
    // unauthenticated (open dummy endpoints), so spring-security isn't on
    // the classpath and those exceptions can never be thrown. If auth lands
    // here later, add them at that point — copy the shape from
    // loyalty-service's GlobalExceptionHandler.

    /**
     * Outbound failures from {@link LoyaltyServiceClient} (shop-checkout flow).
     * Preserve loyalty-service's upstream
     * status (a 4xx insufficient-points stays a 4xx; a 503-on-unreachable stays
     * 503), fall back to 502 for unknown / synthetic codes. Without this the
     * exception would fall through to the catch-all and surface as a generic
     * 500 — which (a) lies to the client about which side broke and (b) hides
     * a 4xx business rejection in the 5xx bucket where on-call would mis-triage.
     */
    @ExceptionHandler(LoyaltyServiceClient.LoyaltyCheckoutException.class)
    public ResponseEntity<ApiResult<Void>> handle(LoyaltyServiceClient.LoyaltyCheckoutException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode());
        if (status == null) status = HttpStatus.BAD_GATEWAY;
        log.warn("loyalty shop-checkout failed status={} message={}",
                status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(ApiResult.<Void>builder()
                .code(status.value() + " " + status.name())
                .message(ex.getMessage() == null ? "loyalty checkout error" : ex.getMessage())
                .data(null)
                .build());
    }

    /**
     * Outbound failures from {@link BookingServiceClient#confirmBooking} —
     * "Booking not found", "Seat hold expired", "Only PENDING bookings can be
     * confirmed", etc. Same envelope pattern: preserve booking-service's
     * upstream status so a 4xx ("hold expired") doesn't get misreported as a
     * 5xx server error, fall back to 502 for connectivity.
     */
    @ExceptionHandler(BookingServiceClient.BookingConfirmationException.class)
    public ResponseEntity<ApiResult<Void>> handle(BookingServiceClient.BookingConfirmationException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode());
        if (status == null) status = HttpStatus.BAD_GATEWAY;
        log.warn("booking confirm failed status={} message={}",
                status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(ApiResult.<Void>builder()
                .code(status.value() + " " + status.name())
                .message(ex.getMessage() == null ? "booking confirm error" : ex.getMessage())
                .data(null)
                .build());
    }

    /**
     * Spring's {@link ResponseStatusException} extends RuntimeException, so
     * without this handler it would be swallowed by the {@code Exception}
     * catch-all below and surface as a sanitised 500. Honour the embedded
     * status / reason instead. Prefer the typed {@link BadRequestException}
     * at throw sites; this is a defence-in-depth net for Spring's own /
     * library code.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResult<Void>> handle(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
        log.warn("ResponseStatusException status={} reason={}", status.value(), reason);
        return ResponseEntity.status(status).body(ApiResult.<Void>builder()
                .code(status.value() + " " + status.name())
                .message(reason)
                .data(null)
                .build());
    }

    /**
     * Domain rejections from {@link innbucks.paymentservice.service.InnbucksPaymentService}'s
     * payment-code flow. Carries a curated user-facing message AND a deliberate
     * HTTP status code per throw site ("Your booking has expired — please create
     * a new booking and try again" / 409, "InnBucks is temporarily unavailable;
     * please try again shortly" / 503, "A payment for this booking is already
     * in progress or completed" / 409, etc.). Without this handler the
     * exception fell through to the {@code Exception.class} catch-all and the
     * customer saw a generic "internal error" — leaving them no idea whether
     * to retry, refresh, or contact support. Honour the embedded status +
     * message so the message reaches the client untouched.
     */
    @ExceptionHandler(InvalidPaymentRequestException.class)
    public ResponseEntity<ApiResult<Void>> handle(InvalidPaymentRequestException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode());
        if (status == null) status = HttpStatus.BAD_REQUEST;
        log.warn("InnBucks payment request rejected status={} message={}",
                status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(ApiResult.<Void>builder()
                .code(status.value() + " " + status.name())
                .message(ex.getMessage() == null ? status.getReasonPhrase() : ex.getMessage())
                .data(null)
                .build());
    }

    // ── Spring MVC's own request errors ──────────────────────────────────────
    // The catch-all below is an @ExceptionHandler, and the @ExceptionHandler
    // resolver runs BEFORE Spring's DefaultHandlerExceptionResolver, so every
    // one of these used to answer 500 "internal error" for what is the
    // caller's mistake: a bookingId that is not a UUID, a paymentRail that is
    // not one of ours, a body that is not JSON, no Content-Type, the wrong
    // verb. GlobalExceptionHandlerDispatchTest drives each through real
    // dispatch; a handler body is never where this breaks.

    /**
     * A body Jackson could not bind. Same shape as bean validation, keyed by
     * the Jackson path ({@code paymentRail}), so a client renders both with one
     * code path. Neither the body nor Jackson's message is logged or returned:
     * Jackson quotes the offending input, and a body can carry a phone number.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handle(HttpMessageNotReadableException ex) {
        Map<String, String> fields = unreadableFields(ex);
        log.warn("Unreadable request body fields={} cause={}", fields.keySet(),
                ex.getMostSpecificCause().getClass().getSimpleName());
        return validationFailed(fields);
    }

    /** A path variable or query parameter that cannot convert to its type. The value is not echoed. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handle(MethodArgumentTypeMismatchException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ex.getName(), expectation(ex.getRequiredType()));
        log.warn("Parameter type mismatch param={} requiredType={}", ex.getName(),
                ex.getRequiredType() == null ? null : ex.getRequiredType().getSimpleName());
        return validationFailed(fields);
    }

    /** A required query parameter or header that is missing, keyed by its name. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handle(MissingServletRequestParameterException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ex.getParameterName(), "Required.");
        return validationFailed(fields);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handle(MissingRequestHeaderException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ex.getHeaderName(), "Required.");
        return validationFailed(fields);
    }

    /** No Content-Type, or one we do not read. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handle(HttpMediaTypeNotSupportedException ex) {
        log.warn("Unsupported Content-Type {}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiResult.<Void>builder()
                .code("415 UNSUPPORTED_MEDIA_TYPE")
                .message("Send the request body as JSON, with Content-Type: application/json.")
                .data(null)
                .build());
    }

    /**
     * The wrong verb on a real path. {@code Allow} says which verbs work; the
     * attempted verb is caller-chosen and is not echoed.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handle(HttpRequestMethodNotSupportedException ex) {
        var response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            response.allow(ex.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return response.body(ApiResult.<Void>builder()
                .code("405 METHOD_NOT_ALLOWED")
                .message("This method is not allowed here.")
                .data(null)
                .build());
    }

    /** A path no controller serves. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handle(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResult.<Void>builder()
                .code("404 NOT_FOUND")
                .message("Not found")
                .data(null)
                .build());
    }

    private static ResponseEntity<ApiResult<Map<String, String>>> validationFailed(Map<String, String> fields) {
        return ResponseEntity.badRequest().body(ApiResult.<Map<String, String>>builder()
                .code("400 BAD_REQUEST")
                .message("validation failed")
                .data(fields)
                .build());
    }

    /**
     * {@code {"<jackson path>": "<what it must be>"}} for a body Jackson
     * rejected, or {@code {"body": …}} when the failure has no field: broken
     * JSON, JSON of the wrong kind ({@code []}, a bare string), or no body.
     * The twin of booking-service's; change them together.
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
                if (t instanceof tools.jackson.databind.exc.MismatchedInputException) {
                    fields.put("body", "The request body must be a single JSON object.");
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
                if (t instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException) {
                    fields.put("body", "The request body must be a single JSON object.");
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

    private record PathStep(String property, int index) {
    }

    /** {@code seats[0].categoryId}: dotted properties, bracketed indexes. */
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

    /**
     * Last-resort catch-all. Logs the full exception so on-call has the trace
     * + correlationId in MDC, but returns a generic body — clients don't get
     * to see our internal package names, frame addresses, or library versions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handle(Exception ex) {
        log.error("Unhandled exception bubbled to GlobalExceptionHandler", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResult.<Void>builder()
                .code("500 INTERNAL_SERVER_ERROR")
                .message("internal error")
                .data(null)
                .build());
    }
}
