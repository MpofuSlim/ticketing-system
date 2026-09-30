package com.innbucks.userservice.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The customer-support meters. Every emit is guarded: a metrics failure must
 * never change a support outcome.
 *
 * <ul>
 *   <li>{@code user.support.lookup{outcome}} — every support call: {@code ok},
 *       {@code replayed}, or the refusal's errorCode. Alert on
 *       {@code outcome=lookup_rate_limited} with {@code window=day} (an agent at the
 *       daily cap) via {@code user.support.limited}.</li>
 *   <li>{@code user.support.limited{window}} — a 429, by window ({@code 10m}/{@code day}).</li>
 *   <li>{@code user.support.limiter.degraded{op}} — Redis could not answer and the
 *       per-replica in-memory window decided instead. While it counts, the
 *       effective limit is multiplied by the number of replicas. Alerted.</li>
 *   <li>{@code user.support.staff_target_lookup} — a lookup whose resolved keys
 *       match an InnBucks staff account. Alerted.</li>
 *   <li>{@code user.support.reset_delivery{outcome}} — the after-commit send of a
 *       support-requested password-reset email: {@code sent} or {@code failed}.
 *       The agent's response cannot know this (it is written before the send),
 *       so a run of {@code failed} is the only signal that callers are waiting
 *       for codes that never come.</li>
 * </ul>
 */
@Component
public class SupportMetrics {

    private final MeterRegistry registry;

    @org.springframework.beans.factory.annotation.Autowired
    public SupportMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
    }

    /** Test seam: no registry. */
    public static SupportMetrics none() {
        return new SupportMetrics((MeterRegistry) null);
    }

    SupportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void lookup(String outcome) {
        increment("user.support.lookup", "outcome", outcome);
    }

    public void limited(String window) {
        increment("user.support.limited", "window", window);
    }

    public void limiterDegraded(String op) {
        increment("user.support.limiter.degraded", "op", op);
    }

    public void resetDelivery(String outcome) {
        increment("user.support.reset_delivery", "outcome", outcome);
    }

    public void staffTargetLookup() {
        increment("user.support.staff_target_lookup", null, null);
    }

    private void increment(String name, String tag, String value) {
        if (registry == null) return;
        try {
            Counter.Builder b = Counter.builder(name);
            if (tag != null) b.tag(tag, value == null ? "unknown" : value);
            b.register(registry).increment();
        } catch (RuntimeException ignored) {
            // never change the outcome over a meter
        }
    }
}
