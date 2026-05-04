package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.UserServiceApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;

// Read-side client into user-service. Loyalty-service is NOT the system of
// record for users — it asks user-service whether a phone number resolves to
// a real customer before lazily creating its local LoyaltyUser projection.
@Component
@Slf4j
public class UserServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public UserServiceClient(
            @Value("${user-service.base-url:http://localhost:8081}") String baseUrl,
            @Value("${user-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${user-service.read-timeout-ms:5000}") int readTimeoutMs,
            ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.objectMapper = objectMapper;
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
            UserServiceApiResult<CustomerTierResponseDTO> envelope = objectMapper.readValue(
                    body,
                    new TypeReference<UserServiceApiResult<CustomerTierResponseDTO>>() {}
            );
            return Optional.ofNullable(envelope.data());
        } catch (Exception e) {
            log.warn("user-service tier lookup failed phoneNumber={} cause={}", phoneNumber, e.toString());
            return Optional.empty();
        }
    }
}
