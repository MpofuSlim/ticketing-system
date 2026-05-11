package com.innbucks.userservice.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * Thin client over loyalty-service's internal API. Used at login to resolve
 * the merchantId for a MERCHANT_ADMIN whose TenantProfile hasn't been bound
 * yet — the result is cached back to TenantProfile.loyaltyMerchantId so the
 * lookup happens at most once per user.
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
                .build();
        this.internalToken = internalToken;
    }

    /**
     * Resolve the merchantId of the merchant whose admin_email matches {@code email}.
     * Returns empty if no such merchant exists, or if the call fails for any reason
     * — login must remain functional even when loyalty-service is down.
     */
    public Optional<UUID> findMerchantIdByAdminEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("Skipping loyalty merchant lookup; INTERNAL_API_TOKEN is not configured");
            return Optional.empty();
        }
        try {
            MerchantLookupResponse body = http.get()
                    .uri(uri -> uri.path("/loyalty/internal/merchants/by-admin")
                            .queryParam("email", email)
                            .build())
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        // Treat 4xx (including 404 "not bound") as "not found" — no exception.
                    })
                    .body(MerchantLookupResponse.class);
            if (body == null || body.merchantId() == null) return Optional.empty();
            return Optional.of(UUID.fromString(body.merchantId()));
        } catch (HttpClientErrorException.NotFound nf) {
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("Loyalty merchant lookup failed email={} error={}", email, ex.getMessage());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MerchantLookupResponse(String merchantId) {}
}
