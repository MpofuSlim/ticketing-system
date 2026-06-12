package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import innbucks.paymentservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Calls booking-service's PATCH /bookings/{id}/confirm to flip a booking from
 * PENDING to CONFIRMED. The dummy payment endpoint uses this once it's
 * "processed" the (fake) payment.
 *
 * <p>booking-service now requires the {@code X-Internal-Token} shared secret
 * on the confirm path (mirror of loyalty + event-service patterns). The
 * value is read from {@code innbucks.internal-api-token} (env
 * {@code INTERNAL_API_TOKEN}) — same token everywhere else in the ticketing
 * stack — and sent on every confirm call.
 */
@Component
@Slf4j
public class BookingServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public BookingServiceClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${booking-service.base-url:http://booking-service}") String baseUrl,
            @Value("${booking-service.connect-timeout-ms:2000}") int connectMs,
            @Value("${booking-service.read-timeout-ms:5000}") int readMs,
            @Value("${innbucks.internal-api-token:}") String internalToken,
            ObjectMapper objectMapper) {
        // JDK HttpClient supports PATCH; SimpleClientHttpRequestFactory's
        // underlying HttpURLConnection rejects it ("Invalid HTTP method:
        // PATCH"). RestClient applies the request factory's read timeout
        // per-request, so we wire connect timeout on the HttpClient and
        // read timeout on the factory.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(readMs));
        // Clone the load-balanced builder so "booking-service" resolves through
        // Eureka; clone() keeps the LB interceptor while letting us set a
        // per-client request factory (JDK HttpClient — needed for PATCH).
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    /**
     * Read-only S2S fetch of a booking (GET /bookings/internal/{id}, gated by
     * X-Internal-Token) — used by the InnBucks payment path to
     * resolve {@code totalAmount} + {@code currency} BEFORE debiting veengu
     * (debit must know the amount; confirm cannot precede payment). Returns
     * the parsed data map (same shape as confirmBooking) on 2xx, throws
     * BookingConfirmationException on non-2xx so the caller can surface
     * "Booking not found" / "Booking already confirmed" etc.
     */
    public Map<String, Object> getBooking(UUID bookingId) {
        try {
            String body = restClient.get()
                    .uri("/bookings/internal/{id}", bookingId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            ApiResult<Map<String, Object>> envelope = objectMapper.readValue(
                    body, new TypeReference<ApiResult<Map<String, Object>>>() {});
            return envelope.getData() != null ? envelope.getData() : Map.of();
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            log.warn("booking-service get failed bookingId={} status={} detail={}",
                    bookingId, e.getStatusCode().value(), detail);
            throw new BookingConfirmationException(detail, e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("booking-service get errored bookingId={} cause={}", bookingId, e.toString());
            throw new BookingConfirmationException(
                    "Unable to reach booking-service to fetch the booking", 503);
        }
    }

    /**
     * Extend the booking's seat hold to at least {@code holdUntil} — called
     * IMMEDIATELY before minting an InnBucks payment code so the hold provably
     * outlives the code the customer is shown (the hold-5min/code-10min
     * paid-but-no-ticket gap). 409/404 from booking-service means the booking
     * is already expired/cancelled/confirmed: the caller refuses the payment
     * BEFORE any money moves.
     */
    public void extendHold(UUID bookingId, java.time.Instant holdUntil) {
        try {
            String body = restClient.patch()
                    .uri("/bookings/internal/{id}/extend-hold", bookingId)
                    .header("X-Internal-Token", internalToken)
                    .header("Content-Type", "application/json")
                    .body(Map.of("holdUntil", java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                            .format(java.time.LocalDateTime.ofInstant(holdUntil, java.time.ZoneOffset.UTC))))
                    .retrieve()
                    .body(String.class);
            log.debug("booking-service hold extended bookingId={} holdUntil={}", bookingId, holdUntil);
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            log.warn("booking-service extend-hold refused bookingId={} status={} detail={}",
                    bookingId, e.getStatusCode().value(), detail);
            throw new BookingConfirmationException(detail, e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("booking-service extend-hold errored bookingId={} cause={}", bookingId, e.toString());
            throw new BookingConfirmationException(
                    "Unable to reach booking-service to extend the seat hold", 503);
        }
    }

    /**
     * Returns the parsed envelope on a 2xx, or throws BookingConfirmationException
     * on a non-2xx so the caller can surface booking-service's reason
     * (e.g. "Seat hold expired", "Booking not found").
     */
    public Map<String, Object> confirmBooking(UUID bookingId) {
        try {
            String body = restClient.patch()
                    .uri("/bookings/{id}/confirm", bookingId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            ApiResult<Map<String, Object>> envelope = objectMapper.readValue(
                    body, new TypeReference<ApiResult<Map<String, Object>>>() {});
            return envelope.getData() != null ? envelope.getData() : Map.of();
        } catch (RestClientResponseException e) {
            // Surface booking-service's error envelope verbatim if we can parse it.
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            log.warn("booking-service confirm failed bookingId={} status={} detail={}",
                    bookingId, e.getStatusCode().value(), detail);
            throw new BookingConfirmationException(detail, e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("booking-service confirm errored bookingId={} cause={}", bookingId, e.toString());
            throw new BookingConfirmationException(
                    "Unable to reach booking-service to confirm the booking", 503);
        }
    }

    private Optional<String> parseErrorMessage(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            ApiResult<Object> envelope = objectMapper.readValue(body,
                    new TypeReference<ApiResult<Object>>() {});
            return Optional.ofNullable(envelope.getMessage());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static class BookingConfirmationException extends RuntimeException {
        private final int statusCode;
        public BookingConfirmationException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }
}
