package com.innbucks.eventservice.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    /** Fixed JWT issuer (iss) required on every token this service verifies.
     *  Minted by user-service; a token lacking it (or with a different value)
     *  is rejected even when the shared HS256 signature checks out. */
    public static final String TOKEN_ISSUER = "innbucks-ticketing";

    /** Fixed JWT audience (aud) required on every token this service verifies. */
    public static final String TOKEN_AUDIENCE = "innbucks-app";

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String extractEmail(String token) {
        return getClaims(token).getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Object raw = getClaims(token).get("roles");
        if (raw instanceof Collection<?> c) {
            return c.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractServices(String token) {
        Object raw = getClaims(token).get("services");
        if (raw instanceof Collection<?> c) {
            return c.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    public String extractRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    public Integer extractTier(String token) {
        return getClaims(token).get("tier", Integer.class);
    }

    public Boolean extractVerified(String token) {
        return getClaims(token).get("verified", Boolean.class);
    }

    /** The user's registered country, carried as a JWT claim by user-service. */
    public String extractCountry(String token) {
        return getClaims(token).get("country", String.class);
    }

    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    /**
     * Extract the {@code homeCountry} claim (ISO 3166-1 alpha-2, e.g.
     * {@code ZW}) — the customer's MSISDN-derived routing key set by
     * user-service's JwtUtil at mint time. Returns null on any failure or
     * when the claim is absent (legacy tokens, staff tokens without an
     * MSISDN, customers whose phone prefix isn't a known InnBucks market).
     * JwtFilter pushes it into MDC for the request's lifetime.
     *
     * <p>Distinct from {@link #extractCountry(String)} — that's the
     * free-text account-metadata claim from {@code users.country}
     * (e.g. "Zimbabwe"); this is the purpose-built ISO routing key.
     */
    public String extractHomeCountry(String token) {
        try {
            String home = getClaims(token).get("homeCountry", String.class);
            return (home == null || home.isBlank()) ? null : home;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Stable cross-service identifier for the caller. Returns null on tokens
     * minted before V20 (user-service migration that added user_uuid) — the
     * caller must treat null as "use the legacy email-based code path".
     */
    public UUID extractUserUuid(String token) {
        return extractUuidClaim(token, "userUuid");
    }

    /**
     * True when the JWT carries the {@code mustChangePassword} claim. The
     * filter uses this to gate every authenticated request — a user who
     * hasn't rotated their temp password may not call any endpoint in this
     * service. Returns false for absent / unparseable claims.
     */
    public boolean extractMustChangePassword(String token) {
        try {
            Boolean v = getClaims(token).get("mustChangePassword", Boolean.class);
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Team-scoping identifier. For an EVENT_ORGANIZER this equals the
     * caller's own user_uuid; for a TEAM_MEMBER it equals the parent
     * organizer's user_uuid (so a team member's scan/list-my-events flows
     * resolve to their organizer's events without a cross-service lookup).
     * Null for non-organizer-tree roles.
     */
    public UUID extractOrganizerUuid(String token) {
        return extractUuidClaim(token, "organizerUuid");
    }

    private UUID extractUuidClaim(String token, String name) {
        try {
            String raw = getClaims(token).get(name, String.class);
            if (raw == null || raw.isBlank()) return null;
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .requireIssuer(TOKEN_ISSUER)
                .requireAudience(TOKEN_AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
