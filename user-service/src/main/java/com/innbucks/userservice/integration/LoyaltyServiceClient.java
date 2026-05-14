package com.innbucks.userservice.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.innbucks.userservice.config.CorrelationIdPropagatingInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin client over loyalty-service's internal API. Used by ShopStaffService to resolve
 * a shop's merchantId/tenantId when onboarding a SHOP_ADMIN.
 */
@Component
@Slf4j
public class LoyaltyServiceClient {

    private final RestClient http;
    private final String internalToken;

    public LoyaltyServiceClient(@Value("${loyalty-service.base-url:http://localhost:8086}") String baseUrl,
                                @Value("${loyalty-service.connect-timeout-ms:2000}") int connectTimeoutMs,
                                @Value("${loyalty-service.read-timeout-ms:3000}") int readTimeoutMs,
                                @Value("${innbucks.internal-api-token:}") String internalToken) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.internalToken = internalToken;
    }

    /**
     * Resolve a shop by id. Used by ShopStaffService when MERCHANT_ADMIN creates a
     * SHOP_ADMIN — we need the shop's merchantId to stamp on the new user. Returns
     * empty on any failure (including 404 and network errors) so callers can map to
     * a clear 4xx without crashing on a downstream outage.
     */
    public Optional<ShopLookupResponse> findShop(UUID shopId) {
        if (shopId == null) return Optional.empty();
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("Skipping loyalty shop lookup; INTERNAL_API_TOKEN is not configured");
            return Optional.empty();
        }
        try {
            ShopLookupResponse body = http.get()
                    .uri("/loyalty/internal/shops/{id}", shopId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {})
                    .body(ShopLookupResponse.class);
            return Optional.ofNullable(body);
        } catch (HttpClientErrorException.NotFound nf) {
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("Loyalty shop lookup failed shopId={} error={}", shopId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fire-and-log promote webhook: tells loyalty-service that a phone has
     * completed registration so every PENDING LoyaltyUser row matching that
     * phone — across every tenant — flips to ACTIVE. Idempotent on the
     * loyalty side; replays return promoted=0.
     *
     * <p>Best-effort: a network blip or a missing token here MUST NOT fail
     * the calling OTP / registration flow. The customer is registered
     * regardless; if the webhook didn't go through, an operator can replay it
     * (or the next scheduled reconciliation job will pick it up).
     *
     * @return true if loyalty-service confirmed the call, false on any failure.
     */
    public boolean promoteUserByPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("promoteUserByPhone called with blank phone — skipping");
            return false;
        }
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("Skipping loyalty promote webhook; INTERNAL_API_TOKEN is not configured");
            return false;
        }
        try {
            http.post()
                    .uri("/loyalty/internal/users/promote")
                    .header("X-Internal-Token", internalToken)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("phoneNumber", phoneNumber))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Loyalty promote webhook OK phone={}", phoneNumber);
            return true;
        } catch (Exception ex) {
            // Don't rethrow — registration succeeds even if loyalty is down.
            log.warn("Loyalty promote webhook failed phone={} error={}", phoneNumber, ex.getMessage());
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShopLookupResponse(String shopId, String merchantId, String tenantId, String status) {}
}
