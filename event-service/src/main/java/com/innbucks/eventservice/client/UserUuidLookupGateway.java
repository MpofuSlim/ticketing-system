package com.innbucks.eventservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service client for the team-member event-assignment lookup
 * event-service makes against user-service. Calls
 * {@code GET /users/internal/team-members/{uuid}/assigned-events} with the
 * shared {@code X-Internal-Token} header — gated by the gateway's
 * {@code user-internal-deny} so an external attacker can never reach the
 * endpoint even though the secret check would reject the call anyway.
 *
 * <p>No circuit breaker: the call fails CLOSED (empty list) on any error,
 * which is the desired deny-by-default for team-member event access. The
 * organizer-detail enrichment ({@link OrganizerGateway}) is the
 * latency-sensitive hot path that needs the breaker.
 */
@Component
@Slf4j
public class UserUuidLookupGateway {

    private final RestTemplate restTemplate;
    private final String userServiceBaseUrl;
    private final String internalToken;

    public UserUuidLookupGateway(
            RestTemplate restTemplate,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.userServiceBaseUrl = userServiceBaseUrl;
        this.internalToken = internalToken;
    }

    /**
     * Returns the event UUIDs a team member is assigned to. Empty list means
     * the team member has no scan access (deny-by-default). Empty list is
     * ALSO returned on any S2S failure — the team-member /events/my caller
     * then sees no events, which is safer than leaking the wider organizer
     * set during a user-service outage.
     */
    public List<UUID> assignedEventIdsFor(UUID teamMemberUuid) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        String url = userServiceBaseUrl + "/users/internal/team-members/"
                + teamMemberUuid + "/assigned-events";
        try {
            Map<String, Object> raw = restTemplate
                    .exchange(url, HttpMethod.GET, request, Map.class)
                    .getBody();
            if (raw == null) return List.of();
            Object data = raw.get("data");
            if (!(data instanceof List<?> list) || list.isEmpty()) {
                return List.of();
            }
            List<UUID> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                if (item == null) continue;
                try {
                    out.add(UUID.fromString(item.toString()));
                } catch (IllegalArgumentException ignore) {
                    log.warn("Skipping malformed assigned-event uuid for teamMemberUuid={}", teamMemberUuid);
                }
            }
            return out;
        } catch (RestClientException ex) {
            log.warn("assigned-events lookup failed teamMemberUuid={} reason={}",
                    teamMemberUuid, ex.getMessage());
            return List.of();
        }
    }
}
