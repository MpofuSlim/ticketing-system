package com.innbucks.eventservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service client for user-service's email -> user_uuid lookup.
 * Used exclusively by {@link com.innbucks.eventservice.service.TenantUserUuidBackfillRunner}
 * to backfill the {@code events.tenant_user_uuid} column for rows that
 * predate the V6 migration.
 *
 * <p>Calls {@code POST /users/internal/users/by-email} with the shared
 * {@code X-Internal-Token} header — gated by the gateway's
 * {@code user-internal-deny} so an external attacker can never reach the
 * endpoint even though the secret check would reject the call anyway.
 *
 * <p>No circuit breaker: the backfill runner is a best-effort startup job
 * that retries on the next deploy, so a failed user-service call just
 * means the rows it would have migrated stay null one more cycle. The
 * organizer-detail enrichment ({@link OrganizerGateway}) is the
 * latency-sensitive hot path that needs the breaker.
 */
@Component
@Slf4j
public class UserUuidLookupGateway {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String userServiceBaseUrl;
    private final String internalToken;

    public UserUuidLookupGateway(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.userServiceBaseUrl = userServiceBaseUrl;
        this.internalToken = internalToken;
    }

    /**
     * Returns a map of email -> user_uuid for the supplied emails. Emails
     * that don't resolve to a user are absent from the map (the caller
     * decides whether absent = "skip" or "log + retry"). On any HTTP
     * failure returns an empty map so the backfill runner can carry on
     * with the next batch.
     */
    public Map<String, UUID> resolveUuidsByEmail(Collection<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return Collections.emptyMap();
        }
        Collection<String> deduped = new LinkedHashSet<>();
        for (String e : emails) {
            if (e != null && !e.isBlank()) deduped.add(e);
        }
        if (deduped.isEmpty()) {
            return Collections.emptyMap();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        Map<String, Object> body = Map.of("emails", List.copyOf(deduped));
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        String url = userServiceBaseUrl + "/users/internal/users/by-email";

        try {
            Map<String, Object> raw = restTemplate
                    .exchange(url, HttpMethod.POST, request, Map.class)
                    .getBody();
            if (raw == null) return Collections.emptyMap();
            Object data = raw.get("data");
            if (!(data instanceof List<?> list) || list.isEmpty()) {
                return Collections.emptyMap();
            }
            List<Row> rows = objectMapper.convertValue(list, new TypeReference<List<Row>>() {});
            Map<String, UUID> result = new HashMap<>();
            for (Row row : rows) {
                if (row.email != null && !row.email.isBlank() && row.userUuid != null) {
                    try {
                        result.put(row.email, UUID.fromString(row.userUuid));
                    } catch (IllegalArgumentException ignore) {
                        log.warn("Skipping malformed userUuid for email={}", row.email);
                    }
                }
            }
            return result;
        } catch (RestClientException ex) {
            log.warn("UUID lookup call failed (will retry on next startup): {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private static final class Row {
        public String email;
        public String userUuid;
    }
}
