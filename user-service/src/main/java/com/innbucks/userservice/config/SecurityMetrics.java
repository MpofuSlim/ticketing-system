package com.innbucks.userservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Security-abuse metrics for user-service (OWASP A09 — the "monitoring" half).
 *
 * <p>The {@code audit_events} table already records these events for forensics,
 * but a DB table can't drive a Prometheus alert. These counters export the same
 * signals to {@code /actuator/prometheus} so {@code prometheus/alerts.yaml} can
 * page on a failed-login spike, a lockout storm, token replay, or someone
 * probing the internal-token boundary — the cheapest possible early warning for
 * an attack in progress.
 *
 * <p>Names use the {@code security.} prefix so they dashboard/alert separately
 * from business + Spring-default meters. Counter increments are nanosecond-scale
 * atomics — safe to call on the auth hot path. Tagged counters are resolved
 * lazily so callers don't have to enumerate tag values up front (mirrors
 * {@code LoyaltyMetrics.incFraudRejected}).
 */
@Component
public class SecurityMetrics {

    private final MeterRegistry registry;

    private final Counter accountLocked;
    private final Counter accountUnlocked;
    private final Counter tokenReuse;
    private final Counter mfaFailure;
    private final Counter auditIntegrityBroken;

    public SecurityMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.accountLocked = Counter.builder("security.auth.account.locked")
                .description("Accounts locked after crossing the failed-login threshold")
                .register(registry);
        this.accountUnlocked = Counter.builder("security.auth.account.unlocked")
                .description("Accounts auto-unlocked after the lockout window elapsed")
                .register(registry);
        this.tokenReuse = Counter.builder("security.auth.token.reuse")
                .description("Refresh-token replay detected — the whole family was revoked")
                .register(registry);
        this.mfaFailure = Counter.builder("security.auth.mfa.failure")
                .description("Failed MFA challenge at login (bad TOTP / bad backup code)")
                .register(registry);
        // Integrity signal from the audit-log HMAC verifier. The invariant is
        // zero, so ANY increase is page-worthy — someone altered/deleted an
        // audit row, or the HMAC secret rotated without a re-seal.
        this.auditIntegrityBroken = Counter.builder("security.audit.integrity.broken")
                .description("audit_events rows whose stored HMAC failed verification (tamper signal)")
                .baseUnit("rows")
                .register(registry);
    }

    /** Failed /auth/login, tagged by reason (unknown_identifier, wrong_password, ...). */
    public void loginFailure(String reason) {
        Counter.builder("security.auth.login.failure")
                .description("Failed logins, grouped by reason")
                .tag("reason", reason == null ? "unknown" : reason)
                .register(registry)
                .increment();
    }

    /** X-Internal-Token rejection on a /users/internal/** endpoint, by reason. */
    public void internalTokenFailure(String reason) {
        Counter.builder("security.auth.internal_token.failure")
                .description("Internal S2S token validation failures, grouped by reason")
                .tag("reason", reason == null ? "unknown" : reason)
                .register(registry)
                .increment();
    }

    /** Rate-limit threshold exceeded, tagged by the throttled endpoint. */
    public void rateLimited(String endpoint) {
        Counter.builder("security.auth.rate_limited")
                .description("Requests rejected by the per-identifier rate limiter, by endpoint")
                .tag("endpoint", endpoint == null ? "unknown" : endpoint)
                .register(registry)
                .increment();
    }

    /** A 403 forbidden — an authenticated principal hit something it may not touch. */
    public void authzDenied(String method) {
        Counter.builder("security.authz.denied")
                .description("Access-denied (403) responses, by HTTP method")
                .tag("method", method == null ? "unknown" : method)
                .register(registry)
                .increment();
    }

    public void accountLocked() {
        accountLocked.increment();
    }

    public void accountUnlocked() {
        accountUnlocked.increment();
    }

    public void tokenReuse() {
        tokenReuse.increment();
    }

    public void mfaFailure() {
        mfaFailure.increment();
    }

    /** Called by the audit-integrity verifier for each row that fails HMAC checking. */
    public void auditIntegrityBroken(long count) {
        if (count > 0) auditIntegrityBroken.increment(count);
    }
}
