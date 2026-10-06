package com.innbucks.eventservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.eventservice.dto.OrganizerDTO;
import com.innbucks.eventservice.cache.ReadCacheConfig;
import com.innbucks.eventservice.cache.ReadThroughCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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
import java.util.UUID;

/**
 * Resolves organizer business details (businessName / businessAddress /
 * businessEmail) from user-service for a batch of organizer user_uuids
 * stamped on each event as {@code tenantUserUuid}. event-service attaches
 * the result to every event response so listings carry the owning
 * organizer's details inline.
 *
 * <p>Calls the service-to-service endpoint {@code POST
 * /users/internal/tenants/lookup-by-uuid}, authenticated by the shared
 * {@code X-Internal-Token} header (never a user JWT). Wrapped in a circuit
 * breaker — on any failure (user-service down, timeout, bad token) the
 * fallback returns an empty map and events are served without organizer
 * details rather than failing the whole listing.
 */
@Component
@Slf4j
public class OrganizerGateway {

    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    private final String userServiceBaseUrl;
    private final String internalToken;
    private final ReadThroughCache organizerCache;

    @Autowired
    public OrganizerGateway(
            RestTemplate restTemplate,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            ObjectMapper objectMapper,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${innbucks.internal-api-token:}") String internalToken,
            @Qualifier(ReadCacheConfig.READ_CACHE_MANAGER) CacheManager cacheManager) {
        this.restTemplate = restTemplate;
        this.circuitBreaker = circuitBreakerFactory.create("organizerLookup");
        this.objectMapper = objectMapper;
        this.userServiceBaseUrl = userServiceBaseUrl;
        this.internalToken = internalToken;
        this.organizerCache = ReadThroughCache.of(cacheManager, ReadCacheConfig.ORGANIZERS);
    }

    /** No cache: every lookup calls user-service. */
    public OrganizerGateway(
            RestTemplate restTemplate,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            ObjectMapper objectMapper,
            String userServiceBaseUrl,
            String internalToken) {
        this(restTemplate, circuitBreakerFactory, objectMapper, userServiceBaseUrl, internalToken, null);
    }

    /**
     * Returns a map of {@code userUuid -> organizer details} for the supplied
     * uuids. Organizers with no business profile are simply absent from the map.
     *
     * <p><b>Cached per organizer</b> ({@code events.cache.organizers.ttl}, 5
     * minutes): every public event list and detail resolves its organizers
     * here, and a business profile changes rarely. Only uuids this pod has no
     * fresh entry for are sent to user-service, in one batch. An organizer
     * user-service answered WITHOUT a profile is cached as absent too (that is
     * an answer); a failed call caches nothing. Display data only — this is
     * not used for any ownership or authorization decision, and the profile
     * edit lives in user-service, so a change reaches event responses within
     * the TTL.
     */
    public Map<UUID, OrganizerDTO> organizersByUserUuids(Collection<UUID> userUuids) {
        if (userUuids == null || userUuids.isEmpty()) {
            return Collections.emptyMap();
        }
        // De-dupe (a page is usually all one organizer) and drop nulls.
        Collection<UUID> ids = new LinkedHashSet<>();
        for (UUID id : userUuids) {
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<UUID, OrganizerDTO> result = new HashMap<>();
        List<UUID> misses = new java.util.ArrayList<>();
        for (UUID id : ids) {
            Cache.ValueWrapper hit = organizerCache.lookup(id);
            if (hit == null) {
                misses.add(id);
            } else if (hit.get() instanceof OrganizerProfile profile) {
                result.put(id, profile.toDto());
            }
            // else: cached "no business profile" — stays absent.
        }
        if (misses.isEmpty()) {
            return result;
        }
        result.putAll(circuitBreaker.run(
                () -> fetchAndRemember(misses),
                throwable -> {
                    log.warn("organizerLookup breaker fallback userUuids={}", misses, throwable);
                    return Collections.emptyMap();
                }
        ));
        return result;
    }

    private Map<UUID, OrganizerDTO> fetchAndRemember(List<UUID> userUuids) {
        Map<UUID, OrganizerDTO> fetched = doFetch(userUuids);
        if (fetched == null) {
            // Not an answer (no body / no data list): remember nothing.
            return Collections.emptyMap();
        }
        for (UUID id : userUuids) {
            OrganizerDTO dto = fetched.get(id);
            organizerCache.put(id, dto == null ? null : OrganizerProfile.of(dto));
        }
        return fetched;
    }

    /** An immutable organizer profile, as cached. */
    record OrganizerProfile(String businessName, String businessAddress, String businessEmail) {
        static OrganizerProfile of(OrganizerDTO dto) {
            return new OrganizerProfile(dto.getBusinessName(), dto.getBusinessAddress(), dto.getBusinessEmail());
        }

        OrganizerDTO toDto() {
            return OrganizerDTO.builder()
                    .businessName(businessName)
                    .businessAddress(businessAddress)
                    .businessEmail(businessEmail)
                    .build();
        }
    }

    /** The organizers user-service returned, or null when the response was
     *  not a well-formed answer (missing body or data list). */
    private Map<UUID, OrganizerDTO> doFetch(Collection<UUID> userUuids) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);

        Map<String, Object> body = Map.of("userUuids", List.copyOf(userUuids));
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        String url = userServiceBaseUrl + "/users/internal/tenants/lookup-by-uuid";
        // user-service returns ApiResult<List<TenantLookupDTO>>. Read as a
        // generic map to avoid pulling cross-service envelope generics, then
        // map the data list.
        Map<String, Object> raw = restTemplate
                .exchange(url, HttpMethod.POST, request, Map.class)
                .getBody();
        if (raw == null) {
            return null;
        }
        Object data = raw.get("data");
        if (!(data instanceof List<?> list)) {
            return null;
        }
        if (list.isEmpty()) {
            return Collections.emptyMap();
        }
        List<TenantLookupRow> rows = objectMapper.convertValue(
                list, new TypeReference<List<TenantLookupRow>>() {});
        Map<UUID, OrganizerDTO> result = new HashMap<>();
        for (TenantLookupRow row : rows) {
            if (row.userUuid == null || row.userUuid.isBlank()) {
                continue;
            }
            UUID key;
            try {
                key = UUID.fromString(row.userUuid);
            } catch (IllegalArgumentException ignore) {
                log.warn("Skipping malformed userUuid={} in organizer lookup response", row.userUuid);
                continue;
            }
            result.put(key, OrganizerDTO.builder()
                    .businessName(row.businessName)
                    .businessAddress(row.businessAddress)
                    .businessEmail(row.businessEmail)
                    .build());
        }
        return result;
    }

    // Wire shape of one element in user-service's lookup-by-uuid response data
    // list. Field names mirror the /auth/register payload so the same
    // vocabulary applies on registration (input) and event listings (output).
    // bpoNumber is intentionally NOT here — it's a business registration
    // identifier kept admin-only via /admin/users/merchants.
    private static final class TenantLookupRow {
        public String userUuid;
        public String businessName;
        public String businessAddress;
        public String businessEmail;
    }
}
