package com.innbucks.userservice.service;

/**
 * Network-side fields the {@link AuditService} pulls from the HTTP
 * request and stamps into the audit row. Kept tiny on purpose —
 * the service layer stays HTTP-agnostic and the controller
 * captures the values right where they're available.
 *
 * <p>{@link #ipAddress} mirrors what {@code LoginRateLimiter}
 * already uses (X-Forwarded-For-aware, falling back to
 * {@code remoteAddr}). {@link #userAgent} is the raw header value;
 * the audit table truncates to 512 chars so a malicious caller
 * can't OOM us with a giant UA string.
 */
public record AuditContext(String ipAddress, String userAgent) {

    /** Background jobs / unit tests that don't have an HTTP request. */
    public static AuditContext none() {
        return new AuditContext(null, null);
    }
}
