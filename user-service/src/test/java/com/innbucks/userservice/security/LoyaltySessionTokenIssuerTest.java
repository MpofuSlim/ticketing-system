package com.innbucks.userservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OTP-proved, loyalty-scoped customer session token.
 *
 * <p>Its safety rests entirely on one property: <b>it carries no roles</b>.
 * Every service in the fleet gates customer endpoints on
 * {@code hasRole('CUSTOMER')}, so a role-less token is inert in booking,
 * payment, event and seat without a line of change in any of them, and loyalty
 * alone grants a role for the scope marker. If a future edit ever puts CUSTOMER
 * in that roles list, one SMS silently becomes a full passwordless login for the
 * whole platform — so {@link #carriesNoRoles()} is the test that matters most
 * here.
 */
class LoyaltySessionTokenIssuerTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private static final String PHONE = "+263771234567";

    private LoyaltySessionTokenIssuer issuer;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        issuer = new LoyaltySessionTokenIssuer(jwtUtil);
        ReflectionTestUtils.setField(issuer, "ttlSeconds", 43200L);
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(KEY).build().parseSignedClaims(token).getPayload();
    }

    @Test
    @DisplayName("carries NO roles — this is what keeps it inert outside loyalty")
    void carriesNoRoles() {
        Claims c = claims(issuer.issue(PHONE));

        assertThat(c.get("roles", List.class)).isEmpty();
    }

    @Test
    @DisplayName("carries the loyalty-otp scope and the verified phone")
    void carriesScopeAndPhone() {
        Claims c = claims(issuer.issue(PHONE));

        assertThat(c.get("services", List.class)).containsExactly("loyalty-otp");
        assertThat(c.get("phoneNumber", String.class)).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("the scope constant matches what loyalty's JwtFilter looks for")
    void scopeConstant() {
        // loyalty's JwtFilter.LOYALTY_OTP_SCOPE is in ANOTHER REPOSITORY;
        // nothing but this literal couples them, and a drift presents as every
        // app customer silently losing loyalty access.
        assertThat(LoyaltySessionTokenIssuer.LOYALTY_OTP_SCOPE).isEqualTo("loyalty-otp");
    }

    @Test
    @DisplayName("the phone is the subject — these customers have no email and no user row")
    void phoneIsTheSubject() {
        assertThat(claims(issuer.issue(PHONE)).getSubject()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("carries no userId — so nothing downstream mistakes it for a fleet account")
    void carriesNoUserId() {
        Claims c = claims(issuer.issue(PHONE));

        assertThat(c.get("userUuid")).isNull();
        assertThat(c.get("userId")).isNull();
    }

    @Test
    @DisplayName("honours the configured TTL, NOT the 15-minute jwt.expiration")
    void honoursConfiguredTtl() {
        // Reusing jwt.expiration would mean a fresh SMS every 15 minutes: that
        // value is tuned for a session backed by a refresh token, and there is
        // no refresh path for a customer with no user row.
        Claims c = claims(issuer.issue(PHONE));

        long ttlMillis = c.getExpiration().getTime() - c.getIssuedAt().getTime();
        assertThat(ttlMillis).isBetween(43_000_000L, 43_300_000L);
    }

    @Test
    @DisplayName("a blank phone is refused rather than minting an unscoped token")
    void blankPhoneIsRefused() {
        // A token with no phone would pass loyalty's role grant conditions
        // nowhere and match no account — better to fail loudly at mint time.
        assertThatThrownBy(() -> issuer.issue(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuer.issue("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("two tokens for the same phone differ — each carries its own jti")
    void tokensAreUnique() {
        assertThat(issuer.issue(PHONE)).isNotEqualTo(issuer.issue(PHONE));
    }
}
