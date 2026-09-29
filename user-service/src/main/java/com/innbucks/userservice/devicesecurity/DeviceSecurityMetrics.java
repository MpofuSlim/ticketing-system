package com.innbucks.userservice.devicesecurity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Prometheus signals for DTX device security. {@code evaluated != returned} on
 * the decision counter is watch mode's whole output: how many customers each rule
 * family would stop if it were enforced.
 */
@Component
public class DeviceSecurityMetrics {

    private final MeterRegistry registry;

    public DeviceSecurityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void decision(Decision returned, Decision evaluated, SignInPurpose purpose, SignInContext context) {
        Counter.builder("device_security.decisions")
                .description("Sign-in decisions: what the app was told, and what the engine concluded")
                .tag("returned", returned.name())
                .tag("evaluated", evaluated.name())
                .tag("purpose", purpose.name())
                .tag("context", context.name())
                .register(registry).increment();
    }

    public void otp(String event, OtpChannel channel) {
        Counter.builder("device_security.otp")
                .description("OTP challenge lifecycle (created, sent, send_failed, verified, wrong, dead, ceiling)")
                .tag("event", event)
                .tag("channel", channel == null ? "none" : channel.name())
                .register(registry).increment();
    }

    public void notification(String type, String channel, String outcome) {
        Counter.builder("device_security.notifications")
                .description("Security notifications to customers")
                .tag("type", type)
                .tag("channel", channel)
                .tag("outcome", outcome)
                .register(registry).increment();
    }

    /** A block or ban applied (or, in watch mode, one that would have been). */
    public void enforcement(String action, String reason, boolean enforced, String actor) {
        Counter.builder("device_security.enforcement")
                .description("Blocks, bans, unlocks and removals")
                .tag("action", action)
                .tag("reason", reason == null ? "none" : reason)
                .tag("enforced", String.valueOf(enforced))
                .tag("actor", actor)
                .register(registry).increment();
    }

    /** OTP velocity on one number — the fraud-desk alert of §8.4. */
    public void fraudDeskAlert(String kind) {
        Counter.builder("device_security.fraud_desk_alerts")
                .description("Signals the fraud desk should look at (OTP velocity, SIM-without-PIN)")
                .tag("kind", kind)
                .register(registry).increment();
    }

    public void partnerKeyFailure(String partner, String reason) {
        Counter.builder("device_security.partner_key_failures")
                .description("Calls to broker/USSD endpoints with a missing or wrong x-api-key")
                .tag("partner", partner)
                .tag("reason", reason)
                .register(registry).increment();
    }

    public void loginResult(LoginOutcome outcome) {
        Counter.builder("device_security.login_results")
                .description("User-login outcomes reported by the broker")
                .tag("outcome", outcome.name())
                .register(registry).increment();
    }
}
