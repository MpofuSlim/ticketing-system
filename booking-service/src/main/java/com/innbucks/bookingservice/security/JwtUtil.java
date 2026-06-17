package com.innbucks.bookingservice.security;

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

    // user-service stamps the customer's phoneNumber into the JWT (login /
    // refresh both set it). May be null for older tokens or system users
    // without a phone on file.
    public String extractPhoneNumber(String token) {
        return getClaims(token).get("phoneNumber", String.class);
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
     * Extract the {@code homeCountry} claim (ISO 3166-1 alpha-2, e.g.
     * {@code ZW}) — the customer's MSISDN-derived routing key set by
     * user-service's JwtUtil at mint time. Returns null on any failure or
     * when the claim is absent (legacy tokens, staff tokens without an
     * MSISDN, customers whose phone prefix isn't a known InnBucks market).
     * JwtFilter pushes it into MDC for the request's lifetime.
     */
    public String extractHomeCountry(String token) {
        try {
            String home = getClaims(token).get("homeCountry", String.class);
            return (home == null || home.isBlank()) ? null : home;
        } catch (Exception e) {
            return null;
        }
    }

    /** Stable cross-service identifier of the caller (user_uuid). Null on
     *  legacy tokens minted before user-service's V20. */
    public UUID extractUserUuid(String token) {
        return extractUuidClaim(token, "userUuid");
    }

    /** Display-name claims (emitted on CUSTOMER, TEAM_MEMBER and
     *  EVENT_ORGANIZER tokens by user-service). The scan boundary uses
     *  these to render a human display name on the rejection toast;
     *  callers fall back to the JWT subject (email) when absent. */
    public String extractFirstName(String token) {
        return extractStringClaim(token, "firstName");
    }

    public String extractLastName(String token) {
        return extractStringClaim(token, "lastName");
    }

    private String extractStringClaim(String token, String name) {
        try {
            String raw = getClaims(token).get(name, String.class);
            return (raw == null || raw.isBlank()) ? null : raw;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Team-scoping identifier. EVENT_ORGANIZER: their own user_uuid;
     * TEAM_MEMBER: the parent organizer's user_uuid. Drives the
     * ticket-scan authorization check — must equal the booking's
     * tenant_user_uuid for the scan to land.
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
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
