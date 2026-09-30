package com.innbucks.userservice.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The staff invite token (V44): {@code STI-} + 32 bytes from {@link SecureRandom},
 * Base64URL without padding — the {@code DeviceTrustService} /
 * {@code LoyaltySessionService} shape.
 *
 * <p>At rest only its SHA-256 (hex) is stored. A bare hash is right here: 256
 * bits of randomness leave nothing to enumerate, so a keyed HMAC would buy
 * nothing (the fleet's HMAC rule is for low-entropy secrets such as a six-digit
 * OTP). Compare hashes only; never log the raw token or the link carrying it.
 */
public final class StaffInviteTokens {

    public static final String PREFIX = "STI-";
    private static final int BYTES = 32;
    /** 4-character prefix + 43 Base64URL characters. */
    public static final int LENGTH = PREFIX.length() + 43;

    private static final SecureRandom RANDOM = new SecureRandom();

    private StaffInviteTokens() {
    }

    /** A fresh raw token. */
    public static String generate() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of the raw token, as stored in {@code staff_invites.token_hash}. */
    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * True when {@code raw} has the shape of a token we mint — checked before any
     * lookup, so an obviously malformed value never reaches the database. The
     * answer to a malformed token is the same opaque {@code invite_invalid}.
     */
    public static boolean wellFormed(String raw) {
        if (raw == null || raw.length() != LENGTH || !raw.startsWith(PREFIX)) return false;
        for (int i = PREFIX.length(); i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}
