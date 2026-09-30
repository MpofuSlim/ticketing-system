package com.innbucks.userservice.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The support agent making a call, resolved LIVE from their account (never from
 * token claims alone): the self-action rule compares the customer's keys with
 * the agent's own email, sign-in phone, staff contact phone and userUuid, and
 * those must be what the account holds now.
 *
 * @param subject      {@code authentication.getName()} — the audit actor
 * @param userId       {@code users.id}; null when the subject names no active account
 * @param userUuid     null when unresolved
 * @param email        lower-cased; null when unresolved or none on file
 * @param phone        {@code users.phone_number}; null for a profiled staff account
 * @param contactPhone {@code staff_profiles.contact_phone}; null without a profile
 * @param authorities  what the request was authorized with — the service-layer
 *                     re-check of a higher tier reads these, like DTX's
 *                     {@code fraudAuthority}
 */
public record SupportAgent(String subject, Long userId, UUID userUuid, String email, String phone,
                           String contactPhone, Set<String> authorities) {

    public SupportAgent {
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
        email = email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    /** True when the subject resolved to an active account. {@code /admin/support/**} requires it. */
    public boolean resolved() {
        return userId != null && userUuid != null;
    }

    public boolean holds(String permission) {
        return authorities.contains(permission);
    }

    /**
     * The limiter's key. The account uuid when resolved; otherwise a hash of the
     * subject, so a caller that authenticated without an account row (only
     * possible in a test harness — JwtFilter refuses a subject with no active
     * account) is still counted, and still never counted as someone else.
     */
    public String limiterKey() {
        if (userUuid != null) return userUuid.toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("subject:" + subject).getBytes(StandardCharsets.UTF_8));
            return "sub-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when any of the customer keys is this agent's own identity. */
    public boolean isSelf(SupportCustomerKeys keys) {
        if (keys == null) return false;
        return keys.hasUserUuid(userUuid)
                || (userId != null && keys.userIds().contains(userId))
                || keys.hasEmail(email)
                || keys.hasPhone(phone)
                || keys.hasPhone(contactPhone);
    }
}
