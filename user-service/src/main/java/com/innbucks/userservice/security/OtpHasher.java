package com.innbucks.userservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * One-way HMAC-SHA256 hashing for OTP codes at rest (OWASP A02).
 *
 * <p>OTP codes were previously stored in plaintext in {@code otps.code} — an
 * authentication factor in cleartext, inconsistent with the SHA-256 hashing
 * applied to every other token in this service. A DB read during an OTP's short
 * live window would hand an attacker usable codes for password-reset / PIN-setup
 * flows (i.e. account takeover).
 *
 * <p><b>Why HMAC (keyed), not bare SHA-256:</b> an OTP is only 6 digits — a
 * million-entry space that a fast unkeyed hash (SHA-256) reverses instantly from
 * a rainbow table. A keyed HMAC with a deployment secret the DB never holds
 * makes the stored digest unusable to a DB-read attacker who lacks the key.
 *
 * <p><b>Deterministic lookup:</b> the digest is stable for a given (code, key),
 * so verification hashes the submitted code and lets the existing
 * {@code OtpRepository.consume} SQL match HMAC-to-HMAC — no schema/flow change
 * beyond widening the column to hold the 64-char hex (migration V30). Mirrors
 * {@link NationalIdHasher}; keyed by {@code otp.hmac-secret}.
 */
@Component
public class OtpHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;

    public OtpHasher(@Value("${otp.hmac-secret:change-me-otp-hmac-secret}") String secret) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Lowercase-hex HMAC-SHA256 of the code. Null/blank passes through unchanged. */
    public String hash(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
            byte[] digest = mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is JDK-guaranteed and the key is always non-empty here,
            // so this is unreachable — fail loudly rather than silently store or
            // compare a plaintext OTP.
            throw new IllegalStateException("Failed to HMAC OTP code", e);
        }
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
