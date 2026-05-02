package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Calls booking-service's PATCH /bookings/{id}/confirm to flip a booking from
 * PENDING to CONFIRMED. The dummy payment endpoint uses this once it's
 * "processed" the (fake) payment. The Authorization header from the inbound
 * /payments call is forwarded so booking-service sees the real caller.
 */
@Component
@Slf4j
public class BookingServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BookingServiceClient(
            @Value("${booking-service.base-url:http://localhost:8084}") String baseUrl,
            @Value("${booking-service.connect-timeout-ms:2000}") int connectMs,
            @Value("${booking-service.read-timeout-ms:5000}") int readMs,
            ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(connectMs);
        rf.setReadTimeout(readMs);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the parsed envelope on a 2xx, or throws BookingConfirmationException
     * on a non-2xx so the caller can surface booking-service's reason
     * (e.g. "Seat hold expired", "Booking not found").
     */
    public Map<String, Object> confirmBooking(UUID bookingId, String authHeader) {
        try {
            String body = restClient.patch()
                    .uri("/bookings/{id}/confirm", bookingId)
                    .headers(headers -> {
                        if (authHeader != null && !authHeader.isBlank()) {
                            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
                        }
                    })
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
