package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Calls loyalty-service's POST /loyalty/internal/shop-checkout to settle the
 * loyalty side of a shop payment (earn for cash, burn for points). Uses the
 * shared internal-token header — the request never traverses the api-gateway
 * (the edge blocks /loyalty/internal/**).
 */
@Component
@Slf4j
public class LoyaltyServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public LoyaltyServiceClient(
            @Value("${loyalty-service.base-url:http://localhost:8086}") String baseUrl,
            @Value("${loyalty-service.connect-timeout-ms:2000}") int connectMs,
            @Value("${loyalty-service.read-timeout-ms:5000}") int readMs,
            @Value("${innbucks.internal-api-token:}") String internalToken,
            ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(readMs));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    public record CheckoutResult(UUID shopId, UUID merchantId, UUID tenantId, UUID loyaltyUserId,
                                 BigDecimal cashAmount, BigDecimal pointsRedeemed,
                                 BigDecimal pointsEarned, BigDecimal walletBalanceAfter,
                                 UUID purchaseTransactionId, UUID redemptionTransactionId) {}

    public CheckoutResult shopCheckout(UUID shopId, String msisdn,
                                       BigDecimal cashAmount, BigDecimal pointsAmount,
                                       String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", shopId);
        body.put("phoneNumber", msisdn);
        body.put("cashAmount", cashAmount);
        body.put("pointsAmount", pointsAmount);
        body.put("reference", reference);

        try {
            String resp = restClient.post()
                    .uri("/loyalty/internal/shop-checkout")
                    .header("X-Internal-Token", internalToken)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> parsed = objectMapper.readValue(resp, new TypeReference<>() {});
            return new CheckoutResult(
                    asUuid(parsed.get("shopId")),
                    asUuid(parsed.get("merchantId")),
                    asUuid(parsed.get("tenantId")),
                    asUuid(parsed.get("loyaltyUserId")),
                    asBigDecimal(parsed.get("cashAmount")),
                    asBigDecimal(parsed.get("pointsRedeemed")),
                    asBigDecimal(parsed.get("pointsEarned")),
                    asBigDecimal(parsed.get("walletBalanceAfter")),
                    asUuid(parsed.get("purchaseTransactionId")),
                    asUuid(parsed.get("redemptionTransactionId"))
            );
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString()).orElse(e.getStatusText());
            log.warn("loyalty shop-checkout failed shopId={} msisdn={} status={} detail={}",
                    shopId, msisdn, e.getStatusCode().value(), detail);
            throw new LoyaltyCheckoutException(detail, e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("loyalty shop-checkout errored shopId={} msisdn={} cause={}",
                    shopId, msisdn, e.toString());
            throw new LoyaltyCheckoutException("Unable to reach loyalty-service for checkout", 503);
        }
    }

    private static UUID asUuid(Object v) {
        if (v == null) return null;
        try { return UUID.fromString(v.toString()); } catch (IllegalArgumentException e) { return null; }
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private Optional<String> parseErrorMessage(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});
            Object msg = parsed.get("message");
            return Optional.ofNullable(msg == null ? null : msg.toString());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static class LoyaltyCheckoutException extends RuntimeException {
        private final int statusCode;
        public LoyaltyCheckoutException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }
}
