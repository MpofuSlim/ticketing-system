package com.innbucks.userservice.devicesecurity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs and verifies DTX's own tokens: the login ticket (contract §3 rule 3) and
 * the in-session step-up proof (§5.5). RS256, so the broker holds only a public
 * key — served as a JWKS — and a leak on the broker side cannot mint tickets.
 *
 * <p>Ticket claims (§7.2): {@code iss}, {@code aud}, {@code sub} = the number,
 * {@code jti}, {@code iat}, {@code exp} (two minutes), and {@code msisdn},
 * {@code installId}, {@code purpose}. The install id rides in clear because the
 * broker compares it with the request's {@code x-device-id}, and the ticket is
 * only ever handed to the device that sent that install id.
 */
@Slf4j
public class LoginTicketSigner {

    public static final String CLAIM_MSISDN = "msisdn";
    public static final String CLAIM_INSTALL_ID = "installId";
    public static final String CLAIM_PURPOSE = "purpose";
    public static final String CLAIM_TYPE = "typ";
    public static final String CLAIM_ACTION = "action";
    public static final String TYPE_TICKET = "login_ticket";
    public static final String TYPE_STEP_UP = "step_up";

    private static final char[] ID_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceSecurityProperties.Ticket config;
    private final Clock clock;
    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;

    public LoginTicketSigner(DeviceSecurityProperties.Ticket config, Clock clock) {
        this.config = config;
        this.clock = clock;
        if (config.getPrivateKey() == null || config.getPrivateKey().isBlank()) {
            this.privateKey = null;
            this.publicKey = null;
        } else {
            this.privateKey = parsePrivateKey(config.getPrivateKey());
            this.publicKey = derivePublicKey(privateKey);
        }
    }

    public boolean isConfigured() {
        return privateKey != null;
    }

    /** A random id with a readable prefix: {@code tkt_…}, {@code chl_…}. 130 bits. */
    public static String newId(String prefix) {
        char[] out = new char[26];
        for (int i = 0; i < out.length; i++) {
            out[i] = ID_ALPHABET[RANDOM.nextInt(ID_ALPHABET.length)];
        }
        return prefix + new String(out);
    }

    /** Signs a login ticket. The caller persists {@code jti} so the ticket is single-use. */
    public String signTicket(String jti, String msisdn, String installId, SignInPurpose purpose, Instant expiresAt) {
        requireConfigured();
        Instant now = clock.instant();
        return Jwts.builder()
                .header().keyId(config.getKeyId()).and()
                .issuer(config.getIssuer())
                .audience().add(config.getAudience()).and()
                .subject(msisdn)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(CLAIM_TYPE, TYPE_TICKET)
                .claim(CLAIM_MSISDN, msisdn)
                .claim(CLAIM_INSTALL_ID, installId)
                .claim(CLAIM_PURPOSE, purpose.name())
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /** Signs the proof an in-session step-up returns (§5.5): "this number re-proved possession on this device just now". */
    public String signStepUpProof(String msisdn, String installId, String action, Instant expiresAt) {
        requireConfigured();
        Instant now = clock.instant();
        return Jwts.builder()
                .header().keyId(config.getKeyId()).and()
                .issuer(config.getIssuer())
                .audience().add(config.getAudience()).and()
                .subject(msisdn)
                .id(newId("stp_"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(CLAIM_TYPE, TYPE_STEP_UP)
                .claim(CLAIM_MSISDN, msisdn)
                .claim(CLAIM_INSTALL_ID, installId)
                .claim(CLAIM_ACTION, action)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Verifies a ticket DTX signed: signature, issuer, audience, expiry and type.
     *
     * @throws IllegalArgumentException on any failure; the caller reports one
     *         opaque "invalid" to the broker.
     */
    public Claims verifyTicket(String compact) {
        requireConfigured();
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(config.getIssuer())
                    .requireAudience(config.getAudience())
                    .clock(() -> Date.from(clock.instant()))
                    .clockSkewSeconds(5)
                    .build()
                    .parseSignedClaims(compact)
                    .getPayload();
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new TicketExpiredException();
        } catch (Exception e) {
            throw new IllegalArgumentException("ticket rejected: " + e.getClass().getSimpleName());
        }
        if (!TYPE_TICKET.equals(claims.get(CLAIM_TYPE, String.class)) || claims.getId() == null) {
            throw new IllegalArgumentException("not a login ticket");
        }
        return claims;
    }

    /** The ticket's signature was fine but its two minutes are up. */
    public static class TicketExpiredException extends IllegalArgumentException {
        public TicketExpiredException() {
            super("ticket expired");
        }
    }

    /** The public half as a JWKS, for the broker (§16.3). */
    public Map<String, Object> jwks() {
        if (publicKey == null) {
            return Map.of("keys", List.of());
        }
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", config.getKeyId());
        jwk.put("n", base64Url(publicKey.getModulus()));
        jwk.put("e", base64Url(publicKey.getPublicExponent()));
        return Map.of("keys", List.of(jwk));
    }

    PublicKey publicKey() {
        return publicKey;
    }

    private void requireConfigured() {
        if (privateKey == null) {
            throw new IllegalStateException("device-security.ticket.private-key is not provisioned");
        }
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            String body = pem.replace("\\n", "\n")
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            // Fail at boot, never at the first sign-in.
            throw new IllegalStateException("Invalid device-security.ticket.private-key (expected an RSA PKCS#8 PEM, "
                    + "-----BEGIN PRIVATE KEY-----)", e);
        }
    }

    private static RSAPublicKey derivePublicKey(PrivateKey key) {
        if (!(key instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalStateException("device-security.ticket.private-key must be an RSA CRT key");
        }
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive the ticket public key", e);
        }
    }
}
