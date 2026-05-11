package com.innbucks.userservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.security.Key;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(String email, Collection<String> roles, Collection<String> defaultServices,
                                int tier, boolean verified, String phoneNumber, UUID merchantId) {
        List<String> roleList = roles == null ? List.of()
                : roles.stream().filter(r -> r != null && !r.isBlank()).collect(Collectors.toList());
        List<String> serviceList = defaultServices == null ? List.of()
                : defaultServices.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toList());

        JwtBuilder builder = Jwts.builder()
                .setSubject(email)
                .claim("roles", roleList)
                .claim("services", serviceList)
                .claim("tier", tier)
                .claim("verified", verified);
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            builder.claim("phoneNumber", phoneNumber);
        }
        if (merchantId != null) {
            builder.claim("merchantId", merchantId.toString());
        }
        return builder
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateToken(String email, Collection<String> roles, Collection<String> defaultServices,
                                int tier, boolean verified, String phoneNumber) {
        return generateToken(email, roles, defaultServices, tier, verified, phoneNumber, null);
    }

    // Convenience overload for single-role callers (kept for tests).
    public String generateToken(String email, String role, int tier, boolean verified, String phoneNumber) {
        return generateToken(email, role == null ? List.of() : List.of(role), List.of(), tier, verified, phoneNumber, null);
    }

    public String generateToken(String email, String role, int tier, boolean verified) {
        return generateToken(email, role, tier, verified, null);
    }

    public String extractEmail(String token) {
        return getClaims(token).getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Object raw = getClaims(token).get("roles");
        if (raw instanceof Collection<?> c) {
            List<String> out = new ArrayList<>();
            for (Object o : c) {
                if (o != null) out.add(o.toString());
            }
            return out;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractServices(String token) {
        Object raw = getClaims(token).get("services");
        if (raw instanceof Collection<?> c) {
            List<String> out = new ArrayList<>();
            for (Object o : c) {
                if (o != null) out.add(o.toString());
            }
            return out;
        }
        return Collections.emptyList();
    }

    public Integer extractTier(String token) {
        return getClaims(token).get("tier", Integer.class);
    }

    public Boolean extractVerified(String token) {
        return getClaims(token).get("verified", Boolean.class);
    }

    public String extractPhoneNumber(String token) {
        return getClaims(token).get("phoneNumber", String.class);
    }

    public UUID extractMerchantId(String token) {
        String raw = getClaims(token).get("merchantId", String.class);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Date extractExpiration(String token) {
        return getClaims(token).getExpiration();
    }

    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
