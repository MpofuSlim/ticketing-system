package com.innbucks.seatservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.seatservice.config.CorrelationIdPropagatingInterceptor;
import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.CustomerTierResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;

/**
 * Calls user-service to read the current registration tier from the system of
 * record. Used by {@code TierAccessInterceptor} so tier gates reflect the
 * latest DB state instead of the (possibly stale) JWT tier claim.
 */
@Component
@Slf4j
public class UserServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public UserServiceClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${user-service.base-url:http://user-service}") String baseUrl,
            @Value("${user-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${user-service.read-timeout-ms:5000}") int readTimeoutMs,
            ObjectMapper objectMapper) {
        // Clone the load-balanced builder so "user-service" resolves through
        // Eureka; clone() preserves the LB interceptor alongside our per-client
        // request factory and correlation-id interceptor.
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(buildRequestFactory(connectTimeoutMs, readTimeoutMs))
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.objectMapper = objectMapper;
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory buildRequestFactory(int connectMs, int readMs) {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return f;
    }

    public Optional<CustomerTierResponseDTO> getCustomerTier(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return Optional.empty();
        }
        try {
            String uri = UriComponentsBuilder.fromPath("/auth/customer/tier")
                    .queryParam("phoneNumber", phoneNumber)
                    .build()
                    .toUriString();
            String body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return Optional.empty();
            }
            ApiResult<CustomerTierResponseDTO> envelope = objectMapper.readValue(
                    body,
                    new TypeReference<ApiResult<CustomerTierResponseDTO>>() {}
            );
            return Optional.ofNullable(envelope.getData());
        } catch (Exception e) {
            log.warn("user-service tier lookup failed phoneNumber={} cause={}", phoneNumber, e.toString());
            return Optional.empty();
        }
    }
}
