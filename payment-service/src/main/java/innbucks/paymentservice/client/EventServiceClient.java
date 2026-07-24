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

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only S2S lookup of an event's settlement metadata
 * ({@code GET /events/internal/{id}}, gated by {@code X-Internal-Token} — the
 * same "three files agree" internal contract booking-service uses for this
 * endpoint). Used by the InnBucks payment path to embed the event's
 * {@code settlementCode} in the code-generation reference
 * ({@code TKZ-<CODE>-<shortId>}) so the merchant statement groups per event.
 *
 * <p><b>Strictly best-effort:</b> every failure — event-service down, event
 * missing, envelope reshaped — returns {@link Optional#empty()}. A payment
 * must never be blocked or slowed materially by settlement tagging, hence the
 * deliberately tight timeouts.
 */
@Component
@Slf4j
public class EventServiceClient {

    /** The two event fields the payment path consumes; everything else is ignored. */
    public record EventSettlementInfo(String settlementCode, String title) {
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public EventServiceClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${event-service.base-url:http://event-service}") String baseUrl,
            @Value("${event-service.connect-timeout-ms:2000}") int connectMs,
            @Value("${event-service.read-timeout-ms:3000}") int readMs,
            @Value("${innbucks.internal-api-token:}") String internalToken,
            ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(readMs));
        // Clone the load-balanced builder so "event-service" resolves through
        // Eureka while this client keeps its own (tighter) timeouts.
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    public Optional<EventSettlementInfo> getSettlementInfo(UUID eventId) {
        if (eventId == null) {
            return Optional.empty();
        }
        try {
            String body = restClient.get()
                    .uri("/events/internal/{id}", eventId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            ApiResult<Map<String, Object>> envelope = objectMapper.readValue(
                    body, new TypeReference<ApiResult<Map<String, Object>>>() {});
            Map<String, Object> data = envelope.getData();
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(new EventSettlementInfo(
                    asString(data.get("settlementCode")),
                    asString(data.get("title"))));
        } catch (Exception e) {
            log.warn("event-service settlement lookup failed eventId={} — reference falls back "
                    + "to the event-id tag: {}", eventId, e.toString());
            return Optional.empty();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
