package innbucks.paymentservice.exception;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.client.OradianMiddlewareException;
import innbucks.paymentservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

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
     * Outbound failures from {@link innbucks.paymentservice.client.OradianMiddlewareClient}.
     * Preserves the upstream HTTP status when we recognise it (so a 400 from
     * Oradian validation stays a 400, a 401 stays a 401, etc.); falls back to
     * 502 Bad Gateway for connectivity errors / empty bodies / unknown
     * statuses, which is the canonical "upstream is having a moment" code.
     */
    @ExceptionHandler(OradianMiddlewareException.class)
    public ResponseEntity<ApiResult<Void>> handle(OradianMiddlewareException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode());
        if (status == null) status = HttpStatus.BAD_GATEWAY;
        log.warn("Oradian middleware call failed status={} message={}",
                status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(ApiResult.<Void>builder()
                .code(status.value() + " " + status.name())
                .message(ex.getMessage() == null ? "oradian middleware error" : ex.getMessage())
                .data(null)
                .build());
    }

    /**
     * Outbound failures from {@link LoyaltyServiceClient} (shop-checkout flow).
     * Same shape as the Oradian handler — preserve loyalty-service's upstream
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
