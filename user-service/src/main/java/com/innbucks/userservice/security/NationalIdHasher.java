package com.innbucks.userservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * One-way HMAC-SHA256 hashing for national IDs at rest.
 *
 * <p>National ID is PII that was previously stored in plaintext in
 * {@code customer_profiles.national_id}. Application code never reads the stored
 * value back — the core-banking customer-create call uses the raw value straight
 * off the registration request — so a one-way HMAC is sufficient and removes the
 * at-rest plaintext exposure (OWASP A02).
 *
 * <p><b>Format:</b> {@code "hmac:" + lowercase-hex(HMAC-SHA256(value, secret))}.
 * <ul>
 *   <li>The {@code hmac:} sentinel distinguishes hashed values from any legacy
 *       plaintext rows created before this shipped, so {@link #hash} is
 *       idempotent and a future backfill can skip already-hashed rows.</li>
 *   <li>The hex digest is byte-for-byte identical to Postgres pgcrypto's
 *       {@code encode(hmac(value, secret, 'sha256'), 'hex')} and openssl's
 *       {@code printf %s value | openssl dgst -sha256 -hmac secret}, so existing
 *       rows (if any ever predate this) can be backfilled in pure SQL with the
 *       same {@code NATIONAL_ID_HMAC_SECRET}.</li>
 * </ul>
 *
 * <p><b>HMAC (keyed), not bare SHA-256:</b> a national-ID space is small and
 * structured enough to be rainbow-tabled / brute-forced if hashed unkeyed. The
 * deployment secret makes the digest unforgeable and undedupable without it.
 */
@Component
public class NationalIdHasher {

    static final String PREFIX = "hmac:";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;

    public NationalIdHasher(
            @Value("${national-id.hmac-secret:change-me-national-id-hmac-secret}") String secret) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the {@code hmac:}-prefixed HMAC of {@code raw}, or {@code raw}
     * unchanged when it is null / blank (nothing to protect) or already hashed
     * (idempotent — guards against a double-hash if the value round-trips).
     */
    public String hash(String raw) {
        if (raw == null || raw.isBlank() || isHashed(raw)) {
            return raw;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
            byte[] digest = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            return PREFIX + toHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is JDK-guaranteed and the key is always non-empty here,
            // so this is unreachable — fail loudly rather than silently fall back
            // to storing plaintext PII.
            throw new IllegalStateException("Failed to HMAC national ID", e);
        }
    }

    /** True iff {@code value} is already an {@code hmac:}-prefixed digest. */
    public boolean isHashed(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
