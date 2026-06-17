package com.innbucks.userservice.security;

import com.innbucks.userservice.util.MsisdnCountryResolver;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
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

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    /** Token type marker. Access tokens omit the claim (treated as access);
     *  refresh tokens carry {@code type=refresh} and are accepted only by
     *  {@code /auth/refresh}. */
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Mints a refresh token. Carries only the subject (email or phone) and a
     * {@code type=refresh} claim — no roles/tier/etc., because those are
     * re-read from the database when the refresh token is exchanged for a
     * fresh access token.
     */
    public String generateRefreshToken(String subject) {
        // A random jti guarantees the JWT (and therefore its SHA-256 hash) is
        // unique even for two refresh tokens minted in the same second for
        // the same subject — critical for the unique index on token_hash.
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .claim("type", TOKEN_TYPE_REFRESH)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    public String extractType(String token) {
        return getClaims(token).get("type", String.class);
    }

    public boolean isRefreshToken(String token) {
        return TOKEN_TYPE_REFRESH.equals(extractType(token));
    }

    public String generateToken(String email, Collection<String> roles, Collection<String> defaultServices,
                                int tier, boolean verified, String phoneNumber,
                                UUID merchantId, UUID shopId,
                                String firstName, String middleName, String lastName,
                                long tokenVersion, String country,
                                UUID userUuid, UUID organizerUuid,
                                boolean mustChangePassword) {
        List<String> roleList = roles == null ? List.of()
                : roles.stream().filter(r -> r != null && !r.isBlank()).collect(Collectors.toList());
        List<String> serviceList = defaultServices == null ? List.of()
                : defaultServices.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toList());

        JwtBuilder builder = Jwts.builder()
                .subject(email)
                .claim("roles", roleList)
                .claim("services", serviceList)
                .claim("tier", tier)
                .claim("verified", verified)
                // Per-user session epoch. JwtFilter rejects tokens whose
                // value is stale relative to users.token_version — that's
                // how a new login revokes the previous device's session
                // without a per-token denylist entry.
                .claim("tokenVersion", tokenVersion);
        // Stable cross-service identifier — every downstream service uses
        // this to refer to the caller instead of the JWT subject (email),
        // which is mutable when the user edits their profile. Emitted as
        // string for JSON portability (jackson encodes UUIDs as quoted
        // strings; java.util.UUID#toString is the canonical hyphenated
        // form). Omitted for legacy callers that haven't been migrated to
        // pass a UUID yet, but every production mint path supplies it.
        if (userUuid != null) {
            builder.claim("userUuid", userUuid.toString());
        }
        // The "team scoping" claim. For an EVENT_ORGANIZER this is their
        // own userUuid; for a TEAM_MEMBER it's the parent organizer's
        // userUuid (taken from User.createdByOrganizerUuid at login).
        // Booking-service reads this to authorize ticket scans —
        // events.tenant_user_uuid must equal the JWT's organizerUuid for
        // the scanner to be allowed to redeem a ticket on that event.
        // Same omit-when-null rule as above.
        if (organizerUuid != null) {
            builder.claim("organizerUuid", organizerUuid.toString());
        }
        // Defence-in-depth gate for the temp-password handoff. When true, every
        // service's JwtFilter (except /auth/** in user-service, which is needed
        // to actually rotate the password) returns 403 password_change_required
        // — so a leaked / intercepted temp password can't be used to call any
        // other endpoint. AuthService bumps token_version on successful
        // password rotation, so the JWT carrying this claim becomes invalid
        // immediately afterward; the user re-logs in with the new password to
        // get a fresh JWT with mustChangePassword=false. Omitted from the wire
        // (claim NOT set) when false to keep the token slim.
        if (mustChangePassword) {
            builder.claim("mustChangePassword", true);
        }
        if (country != null && !country.isBlank()) {
            builder.claim("country", country);
        }
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            builder.claim("phoneNumber", phoneNumber);
        }
        // homeCountry: ISO 3166-1 alpha-2 derived from the MSISDN dialling
        // prefix. Purpose-built routing key for the eventual per-country
        // cell architecture — completely separate from the legacy `country`
        // claim above (which is free-text account metadata from
        // users.country, often blank for customers). Additive: every
        // existing token consumer keeps reading `country`; new consumers
        // that need the routing key read `homeCountry`. Resolver returns
        // empty for unknown prefixes — we omit the claim rather than
        // guess, because guessing on the auth path puts customers in the
        // wrong cell.
        MsisdnCountryResolver.resolve(phoneNumber)
                .ifPresent(iso -> builder.claim("homeCountry", iso));
        if (merchantId != null) {
            builder.claim("merchantId", merchantId.toString());
        }
        if (shopId != null) {
            builder.claim("shopId", shopId.toString());
        }
        // Display-name claims (firstName / middleName / lastName) — emitted on:
        //   CUSTOMER       : the original use case (the FE shows the signed-in
        //                    customer's name on their own dashboard).
        //   TEAM_MEMBER    : gate-staff scanning tickets. Their name shows up
        //                    on the rejection toast ("already scanned by Tariro
        //                    Chikomo at 19:42") rendered to other gate-staff —
        //                    the scanner email is the wrong thing to display
        //                    there, both for legibility and for not leaking
        //                    staff login addresses across the gate-staff group.
        //   EVENT_ORGANIZER: organizers can scan their own events directly
        //                    (small-event case) and need the same name capture
        //                    as their team. They also see their own name in
        //                    the FE organizer header.
        // Other staff (MERCHANT_ADMIN, SHOP_ADMIN, SHOP_USER, SUPER_ADMIN) stay
        // omitted — their tokens don't surface a display name anywhere today,
        // and we'd rather keep them slim + less PII-exposed by default.
        // AuthService further gates this to tier >= 2 for customers (tier 1
        // names are placeholders like "Customer Pending"); JwtUtil enforces
        // the role check independently as a backstop.
        boolean emitDisplayName = roleList.contains("CUSTOMER")
                || roleList.contains("TEAM_MEMBER")
                || roleList.contains("EVENT_ORGANIZER");
        if (emitDisplayName && firstName != null && !firstName.isBlank()) {
            builder.claim("firstName", firstName);
        }
        if (emitDisplayName && middleName != null && !middleName.isBlank()) {
            builder.claim("middleName", middleName);
        }
        if (emitDisplayName && lastName != null && !lastName.isBlank()) {
            builder.claim("lastName", lastName);
        }
        return builder
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Legacy overload — preserved for callers (mainly tests) that don't supply
     * userUuid/organizerUuid/mustChangePassword. Defaults the must-change flag
     * to false so legacy tokens behave the same as before.
     */
    public String generateToken(String email, Collection<String> roles, Collection<String> defaultServices,
                                int tier, boolean verified, String phoneNumber,
                                UUID merchantId, UUID shopId,
                                String firstName, String middleName, String lastName,
                                long tokenVersion, String country,
                                UUID userUuid, UUID organizerUuid) {
        return generateToken(email, roles, defaultServices, tier, verified, phoneNumber,
                merchantId, shopId, firstName, middleName, lastName, tokenVersion, country,
                userUuid, organizerUuid, false);
    }

    /**
     * Legacy overload — same shape as the original 13-arg method, preserved
     * for callers (mainly tests) that don't supply userUuid/organizerUuid.
     * Production paths call the full version above and emit both UUID claims.
     */
    public String generateToken(String email, Collection<String> roles, Collection<String> defaultServices,
                                int tier, boolean verified, String phoneNumber,
                                UUID merchantId, UUID shopId,
                                String firstName, String middleName, String lastName,
                                long tokenVersion, String country) {
        return generateToken(email, roles, defaultServices, tier, verified, phoneNumber,
                merchantId, shopId, firstName, middleName, lastName, tokenVersion, country, null, null);
    }

    public String generateToken(String email, Collection<String> roles, Collection<String> defaultServices,
                                int tier, boolean verified, String phoneNumber,
                                UUID merchantId, UUID shopId,
                                String firstName, String middleName, String lastName,
                                long tokenVersion) {
        return generateToken(email, roles, defaultServices, tier, verified, phoneNumber,
                merchantId, shopId, firstName, middleName, lastName, tokenVersion, null);
    }

    public String generateToken(String email, Collection<String> roles, Collection<String> defaultServices,
                                int tier, boolean verified, String phoneNumber,
                                UUID merchantId, UUID shopId) {
        return generateToken(email, roles, defaultServices, tier, verified, phoneNumber,
                merchantId, shopId, null, null, null, 0L, null);
    }

    public String generateToken(String email, Collection<String> roles, Collection<String> defaultServices,
                                int tier, boolean verified, String phoneNumber, UUID merchantId) {
        return generateToken(email, roles, defaultServices, tier, verified, phoneNumber, merchantId, null);
    }

    public String generateToken(String email, Collection<String> roles, Collection<String> defaultServices,
                                int tier, boolean verified, String phoneNumber) {
        return generateToken(email, roles, defaultServices, tier, verified, phoneNumber, null, null);
    }

    // Convenience overload for single-role callers (kept for tests).
    public String generateToken(String email, String role, int tier, boolean verified, String phoneNumber) {
        return generateToken(email, role == null ? List.of() : List.of(role), List.of(), tier, verified, phoneNumber, null, null);
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

    /**
     * Per-user session epoch. JwtFilter compares this against
     * {@code users.token_version} on each authenticated request; a stale
     * value means a newer login has invalidated this token.
     *
     * <p>Returns {@code 0L} when the claim is missing or unparseable —
     * matches the column default so a token minted before the migration
     * landed (or by a test helper using a shorter generateToken overload)
     * still passes the check on the first request after deploy. The next
     * login bumps the user's column to 1 and any stale tokens are
     * invalidated from then on.
     */
    public long extractTokenVersion(String token) {
        Object raw = getClaims(token).get("tokenVersion");
        if (raw instanceof Number n) return n.longValue();
        if (raw == null) return 0L;
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Optional display-name claim. Emitted for CUSTOMER, TEAM_MEMBER and
     *  EVENT_ORGANIZER tokens (see {@code generateToken} doc); {@code null}
     *  for other staff tokens or tier-1 customers with placeholder names. */
    public String extractFirstName(String token) {
        return getClaims(token).get("firstName", String.class);
    }

    public String extractMiddleName(String token) {
        return getClaims(token).get("middleName", String.class);
    }

    public String extractLastName(String token) {
        return getClaims(token).get("lastName", String.class);
    }

    public Boolean extractVerified(String token) {
        return getClaims(token).get("verified", Boolean.class);
    }

    public String extractPhoneNumber(String token) {
        return getClaims(token).get("phoneNumber", String.class);
    }

    public String extractCountry(String token) {
        return getClaims(token).get("country", String.class);
    }

    /**
     * ISO 3166-1 alpha-2 country code derived from the MSISDN at mint
     * time (e.g. {@code ZW}, {@code KE}). Routing key for the multi-cell
     * deployment model — every service can read it from the verified
     * token without a database lookup. Returns {@code null} for tokens
     * minted before this claim landed, for users with no phone number
     * (staff tokens), or for MSISDNs whose prefix isn't an InnBucks
     * target market. Callers must treat null as "country unknown" —
     * never as a fallback to a default cell.
     */
    public String extractHomeCountry(String token) {
        return getClaims(token).get("homeCountry", String.class);
    }

    public UUID extractMerchantId(String token) {
        return extractUuidClaim(token, "merchantId");
    }

    public UUID extractShopId(String token) {
        return extractUuidClaim(token, "shopId");
    }

    /** Stable cross-service identifier for the caller. Null on tokens minted before V20. */
    public UUID extractUserUuid(String token) {
        return extractUuidClaim(token, "userUuid");
    }

    /**
     * True when the JWT carries the {@code mustChangePassword} claim. Every
     * service's JwtFilter enforces this: tokens with the flag set may not call
     * anything beyond the password-rotation endpoints (in user-service,
     * {@code /auth/**} is excluded from the filter so /auth/change-password
     * and /auth/logout still work). Returns false when the claim is absent
     * or unparseable — pre-claim tokens behave as if the flag is off.
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
     * Team-scoping identifier. Equal to {@link #extractUserUuid(String)} for
     * an EVENT_ORGANIZER; equal to the parent organizer's user_uuid for a
     * TEAM_MEMBER. Null on tokens minted before V20 or for roles outside
     * the event-organizer tree (CUSTOMER, MERCHANT_ADMIN, etc.).
     */
    public UUID extractOrganizerUuid(String token) {
        return extractUuidClaim(token, "organizerUuid");
    }

    private UUID extractUuidClaim(String token, String name) {
        String raw = getClaims(token).get(name, String.class);
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
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
