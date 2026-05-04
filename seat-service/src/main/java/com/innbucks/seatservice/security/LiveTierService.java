package com.innbucks.seatservice.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Authoritative tier lookup against user-service. Every MinTier-protected
 * request hits this; we cache for a few seconds per subject so we don't
 * generate one user-service round-trip per HTTP call. The cache TTL is
 * deliberately short — any tier upgrade made via /auth/customer/register/tierN
 * becomes visible within that window without forcing the user to log out.
 *
 * Returns {@code null} when user-service is unreachable so the caller can
 * fall back to the JWT claim and avoid locking everyone out during an
 * outage.
 */
@Service
@Slf4j
public class LiveTierService {

    private final RestClient restClient;
    private final long cacheTtlSeconds;
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();

    public LiveTierService(
            @Value("${user-service.url:http://localhost:8081}") String userServiceUrl,
            @Value("${tier.cache-ttl-seconds:5}") long cacheTtlSeconds) {
        this.restClient = RestClient.builder().baseUrl(userServiceUrl).build();
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public Integer tierForSubject(String subject) {
        if (subject == null || subject.isBlank()) return null;
        Instant now = Instant.now();
        Cached hit = cache.get(subject);
        if (hit != null && hit.expiresAt.isAfter(now)) {
            return hit.tier;
        }
        try {
            Envelope env = restClient.get()
                    .uri(b -> b.path("/auth/customer/tier/by-subject")
                            .queryParam("subject", subject).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Envelope>() {});
            if (env == null || env.data == null) return null;
            int tier = env.data.currentTier;
            cache.put(subject, new Cached(tier,
                    now.plus(Duration.ofSeconds(Math.max(1, cacheTtlSeconds)))));
            return tier;
        } catch (Exception e) {
            log.warn("LiveTierService lookup failed subject-redacted={} reason={}",
                    redact(subject), e.getMessage());
            return null;
        }
    }

    private static String redact(String s) {
        if (s == null || s.length() < 4) return "***";
        return s.substring(0, 2) + "***" + s.substring(s.length() - 2);
    }

    private record Cached(int tier, Instant expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Envelope {
        public CustomerTierData data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CustomerTierData {
        public String phoneNumber;
        public int currentTier;
        public Integer nextTier;
    }
}
