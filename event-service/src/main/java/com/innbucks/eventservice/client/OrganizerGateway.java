package com.innbucks.eventservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.eventservice.dto.OrganizerDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Resolves organizer business details (businessName / email / address) from
 * user-service for a batch of tenant ids (the JWT subject stamped on each
 * event as {@code tenantId}). event-service attaches the result to every event
 * response so listings carry the owning organizer's details inline.
 *
 * <p>Calls the service-to-service endpoint {@code POST
 * /users/internal/tenants/lookup}, authenticated by the shared
 * {@code X-Internal-Token} header (never a user JWT). Wrapped in a circuit
 * breaker — on any failure (user-service down, timeout, bad token) the fallback
 * returns an empty map and events are served without organizer details rather
 * than failing the whole listing.
 */
@Component
@Slf4j
public class OrganizerGateway {

    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    private final String userServiceBaseUrl;
    private final String internalToken;

    public OrganizerGateway(
            RestTemplate restTemplate,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            ObjectMapper objectMapper,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.circuitBreaker = circuitBreakerFactory.create("organizerLookup");
        this.objectMapper = objectMapper;
        this.userServiceBaseUrl = userServiceBaseUrl;
        this.internalToken = internalToken;
    }

    /**
     * Returns a map of tenantId -> organizer details for the supplied tenant
     * ids. Tenants with no business profile are simply absent from the map.
     */
    public Map<String, OrganizerDTO> organizersByTenantIds(Collection<String> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // De-dupe (a page is usually all one organizer) and drop blanks.
        Collection<String> ids = new LinkedHashSet<>();
        for (String id : tenantIds) {
            if (id != null && !id.isBlank()) ids.add(id);
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return circuitBreaker.run(
                () -> doFetch(ids),
                throwable -> {
                    log.warn("organizerLookup breaker fallback tenantIds={}", ids, throwable);
                    return Collections.emptyMap();
                }
        );
    }

    private Map<String, OrganizerDTO> doFetch(Collection<String> tenantIds) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);

        Map<String, Object> body = Map.of("tenantIds", List.copyOf(tenantIds));
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        String url = userServiceBaseUrl + "/users/internal/tenants/lookup";
        // user-service returns ApiResult<List<TenantLookupDTO>>. Read as a
        // generic map to avoid pulling cross-service envelope generics, then
        // map the data list.
        Map<String, Object> raw = restTemplate
                .exchange(url, HttpMethod.POST, request, Map.class)
                .getBody();
        if (raw == null) {
            return Collections.emptyMap();
        }
        Object data = raw.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty()) {
            return Collections.emptyMap();
        }
        List<TenantLookupRow> rows = objectMapper.convertValue(
                list, new TypeReference<List<TenantLookupRow>>() {});
        Map<String, OrganizerDTO> result = new HashMap<>();
        for (TenantLookupRow row : rows) {
            if (row.tenantId == null || row.tenantId.isBlank()) {
                continue;
            }
            result.put(row.tenantId, OrganizerDTO.builder()
                    .businessName(row.businessName)
                    .email(row.email)
                    .address(row.address)
                    .build());
        }
        return result;
    }

    // Wire shape of one element in user-service's lookup response data list.
    private static final class TenantLookupRow {
        public String tenantId;
        public String businessName;
        public String email;
        public String address;
    }
}
