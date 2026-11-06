package com.innbucks.userservice.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The sales-agent portal invite token (V56): {@code SAI-} + 32 bytes from
 * {@link SecureRandom}, Base64URL without padding — the {@link StaffInviteTokens}
 * shape with its own prefix, so a staff link and an agent link can never be
 * mistaken for each other at either endpoint.
 *
 * <p>At rest only its SHA-256 (hex) is stored. A bare hash is right: 256 bits
 * of randomness leave nothing to enumerate (the fleet's HMAC rule is for
 * low-entropy secrets such as a six-digit OTP). Never log the raw token or the
 * link carrying it.
 */
public final class SalesAgentInviteTokens {

    public static final String PREFIX = "SAI-";
    private static final int BYTES = 32;
    /** 4-character prefix + 43 Base64URL characters. */
    public static final int LENGTH = PREFIX.length() + 43;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SalesAgentInviteTokens() {
    }

    /** A fresh raw token. */
    public static String generate() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of the raw token, as stored in {@code sales_agent_invites.token_hash}. */
    public static String hash(String rawToken) {
        return StaffInviteTokens.hash(rawToken);
    }

    /**
     * True when {@code raw} has the shape of a token we mint, checked before any
     * lookup so a malformed value never reaches the database. The answer to a
     * malformed token is the same opaque {@code invite_invalid}.
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
