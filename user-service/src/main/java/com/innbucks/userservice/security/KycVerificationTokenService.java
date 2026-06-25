package com.innbucks.userservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies the short-lived, single-use <strong>customer KYC-upgrade
 * verification token</strong> — the proof-of-phone-ownership gate on the
 * otherwise-unauthenticated tier-2/3/4 registration endpoints.
 *
 * <p>Without this gate those endpoints identify the customer to mutate purely
 * from a caller-supplied phone, so anyone who knows a victim's MSISDN (the
 * public login identifier) could rewrite their KYC, hijack their email, force a
 * core-banking record, and flip them to "verified" — unauthenticated account
 * takeover. The token closes that: it is minted <em>only</em> by
 * {@code POST /auth/otp/verify} once the caller proves they control the phone by
 * entering the OTP, and is bound to that exact MSISDN. tier-2/3/4 require it and
 * derive the phone from it, ignoring any body/param phone.
 *
 * <p>It is a stateless HS256 JWT signed with the same {@code jwt.secret} as the
 * fleet's access tokens, but carries {@code purpose=customer-kyc-upgrade} plus
 * the standard {@link JwtUtil#TOKEN_ISSUER}/{@link JwtUtil#TOKEN_AUDIENCE}
 * binding, so an access/refresh token (or a token minted for another system
 * that happens to share the secret) is rejected here, and this token is useless
 * as an access token. Replay within the TTL is closed by
 * {@link ConsumedKycTokenStore} at the terminal tier-4 step.
 */
@Component
public class KycVerificationTokenService {

    /** Purpose claim value — pins the token to the KYC-upgrade flow. */
    public static final String PURPOSE = "customer-kyc-upgrade";
    static final String CLAIM_PURPOSE = "purpose";

    /** Short TTL: long enough to walk tier 2 -&gt; 3 -&gt; 4 in one sitting,
     *  short enough that a leaked token is useless minutes later. */
    public static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final SecretKey signingKey;

    public KycVerificationTokenService(@Value("${jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Mint a token bound to {@code phoneNumber}. Called on a successful OTP verify. */
    public String issue(String phoneNumber) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(phoneNumber)
                .claim(CLAIM_PURPOSE, PURPOSE)
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Validate signature / issuer / audience / expiry / purpose and return the
     * bound phone. Throws {@link KycVerificationTokenException} (mapped to HTTP
     * 401) on any failure. Does <em>not</em> enforce single-use — that is
     * {@link ConsumedKycTokenStore}, consumed at the terminal tier-4 step.
     */
    public VerifiedKycToken verify(String token) {
        if (token == null || token.isBlank()) {
            throw new KycVerificationTokenException(
                    KycVerificationTokenException.Reason.MISSING, "verification token is required");
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(JwtUtil.TOKEN_ISSUER)
                    .requireAudience(JwtUtil.TOKEN_AUDIENCE)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new KycVerificationTokenException(
                    KycVerificationTokenException.Reason.EXPIRED, "verification token expired");
        } catch (JwtException ex) {
            // bad signature, wrong/absent issuer or audience, or unparseable
            throw new KycVerificationTokenException(
                    KycVerificationTokenException.Reason.INVALID, "verification token did not validate");
        }

        String purpose = claims.get(CLAIM_PURPOSE, String.class);
        if (!PURPOSE.equals(purpose)) {
            throw new KycVerificationTokenException(
                    KycVerificationTokenException.Reason.PURPOSE_MISMATCH, "expected purpose=" + PURPOSE);
        }
        String phone = claims.getSubject();
        String jti = claims.getId();
        if (phone == null || phone.isBlank() || jti == null || claims.getExpiration() == null) {
            throw new KycVerificationTokenException(
                    KycVerificationTokenException.Reason.MALFORMED, "verification token missing subject/jti/exp");
        }
        return new VerifiedKycToken(phone, jti, claims.getExpiration().toInstant());
    }
}
