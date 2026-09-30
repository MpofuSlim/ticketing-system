package com.innbucks.userservice.support;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Customer support ({@code support.*}, env {@code SUPPORT_*}). Per cell, from
 * {@code deploy/cells/cell.<iso>.env} — a k3s pod gets its whole environment
 * through {@code envFrom}, so a key only in {@code application.yaml} never
 * reaches it. The assertion PRIVATE key is the exception: it comes from the
 * dedicated {@code user-service-support-signing} Secret, never from the cell
 * Secret every pod receives.
 *
 * <p>Validated at boot ({@link #afterPropertiesSet}): a nonsensical limit or a
 * TTL outside its bounds stops the service rather than silently weakening a
 * control.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "support")
public class SupportProperties implements InitializingBean {

    /** {@code SUPPORT_ENABLED}: off answers 404 {@code support_disabled} on every {@code /admin/support/**} call. */
    private boolean enabled = true;

    /**
     * {@code SUPPORT_LOOKUP_BINDING_TTL}: how long a lookup can back a detail
     * read or a write. Short on purpose — a lookup is the agent's proof that the
     * customer is on the line now, not a standing licence to act on them.
     */
    private Duration lookupBindingTtl = Duration.ofMinutes(30);

    private Limiter limiter = new Limiter();

    private Assertion assertion = new Assertion();

    @Getter
    @Setter
    public static class Limiter {
        /** {@code SUPPORT_LIMIT_SHORT_WINDOW}: the short sliding window. */
        private Duration shortWindow = Duration.ofMinutes(10);
        /** {@code SUPPORT_LIMIT_SHORT_WINDOW_MAX}: lookups, detail reads and writes per agent per short window (D11). */
        private int shortWindowMax = 60;
        /** {@code SUPPORT_LIMIT_DAILY_MAX}: the same, per rolling 24 hours (D11). */
        private int dailyMax = 400;
    }

    @Getter
    @Setter
    public static class Assertion {
        /**
         * {@code SUPPORT_ASSERTION_PRIVATE_KEY}: RSA PKCS#8 PEM. From the
         * {@code user-service-support-signing} Secret only. Blank boots, with a
         * HALF-PROVISIONED ERROR, and every S2S support section then renders
         * UNAVAILABLE (none exists before PR 3).
         */
        private String privateKey = "";
        /** {@code SUPPORT_ASSERTION_KEY_ID}: the JWS {@code kid}, so a verifier can hold two keys across a rotation. */
        private String keyId = "support-assertion-1";
        /** Lifetime of one assertion. At most 60 seconds: it is minted per call and spent at once. */
        private Duration ttl = Duration.ofSeconds(60);
    }

    @Override
    public void afterPropertiesSet() {
        if (lookupBindingTtl == null || lookupBindingTtl.isNegative() || lookupBindingTtl.isZero()
                || lookupBindingTtl.compareTo(Duration.ofHours(4)) > 0) {
            throw new IllegalStateException("support.lookup-binding-ttl must be positive and at most PT4H");
        }
        if (limiter.shortWindow == null || limiter.shortWindow.isNegative() || limiter.shortWindow.isZero()
                || limiter.shortWindow.compareTo(Duration.ofDays(1)) >= 0) {
            throw new IllegalStateException("support.limiter.short-window must be positive and shorter than a day");
        }
        if (limiter.shortWindowMax < 1 || limiter.dailyMax < 1) {
            throw new IllegalStateException("support.limiter.short-window-max and daily-max must be at least 1");
        }
        if (assertion.ttl == null || assertion.ttl.isNegative() || assertion.ttl.isZero()
                || assertion.ttl.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalStateException("support.assertion.ttl must be positive and at most 60 seconds");
        }
        if (assertion.keyId == null || assertion.keyId.isBlank()) {
            throw new IllegalStateException("support.assertion.key-id must not be blank");
        }
        if (assertion.privateKey == null) {
            assertion.privateKey = "";
        }
    }
}
