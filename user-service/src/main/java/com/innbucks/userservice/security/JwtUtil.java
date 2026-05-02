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
import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(String email, String role, int tier, boolean verified, String phoneNumber) {
        JwtBuilder builder = Jwts.builder()
                .setSubject(email)
                .claim("role", role)
                .claim("tier", tier)
                .claim("verified", verified);
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            // Optional claim. Customers can register / log in by email or phone;
            // either form gets the phone in the JWT so downstream services
            // (booking-service in particular) can stamp it on payments without
            // an extra round-trip back to user-service.
            builder.claim("phoneNumber", phoneNumber);
        }
        return builder
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // Backwards-compat overload — left in place so older call sites compile.
    // New call sites should use the 5-arg version with phoneNumber.
    public String generateToken(String email, String role, int tier, boolean verified) {
        return generateToken(email, role, tier, verified, null);
    }

    public String extractEmail(String token) {
        return getClaims(token).getSubject();
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

    public String extractPhoneNumber(String token) {
        return getClaims(token).get("phoneNumber", String.class);
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
