package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.DeviceSecurityException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The call-center reads of {@code GET /admin/device-security/**}, brought under
 * the customer-support rules without changing their contract (design §3.2,
 * S14): each read counts against the agent's lookup limit (429
 * {@code lookup_rate_limited} is the only new answer) and writes a
 * {@link SupportAccessLog} row — fail-closed, like every read that hands
 * customer data out. The 429 is thrown before the read runs, so a limited
 * agent learns nothing.
 *
 * <p><b>Not switched off by {@code SUPPORT_ENABLED=false}</b>, deliberately: that
 * switch takes the new {@code /admin/support/**} surface away (404), but these
 * reads existed before it and hand out the same customer data — recording who
 * looked, and capping how fast, is a control on the data, not a feature of the
 * new screen. So the limit and the fail-closed log row apply on every cell.
 *
 * <p>NOT bound to a lookup: those endpoints shipped with the console, and they
 * are keyed by the msisdn (which the agent types, exactly as they would into the
 * search) and an opaque device id — not an enumerable one. Their WRITES already
 * require a note and seal on the chain, and are unchanged.
 */
@Component
public class DeviceSecurityReadAccess {

    public static final String OP_OVERVIEW = "DEVICE_SECURITY_OVERVIEW";
    public static final String OP_EVENTS = "DEVICE_SECURITY_EVENTS";
    public static final String OP_SUPPORT_REF = "DEVICE_SECURITY_SUPPORT_REF";
    public static final String OP_STOPPED = "DEVICE_SECURITY_STOPPED";
    public static final String OP_SAME_HANDSET = "DEVICE_SECURITY_SAME_HANDSET";

    private final SupportAgentResolver agents;
    private final SupportLookupLimiter limiter;
    private final SupportAccessLogWriter accessLog;
    private final SupportMetrics metrics;
    private final Clock clock;
    private final String country;

    public DeviceSecurityReadAccess(SupportAgentResolver agents, SupportLookupLimiter limiter,
                                    SupportAccessLogWriter accessLog, SupportMetrics metrics,
                                    @Qualifier("supportClock") Clock supportClock,
                                    @org.springframework.beans.factory.annotation.Value("${innbucks.country:ZW}")
                                    String country) {
        this.agents = agents;
        this.limiter = limiter;
        this.accessLog = accessLog;
        this.metrics = metrics;
        this.clock = supportClock;
        this.country = country;
    }

    /** The keys a read about a TYPED number reached: that number in E.164, or none when it is not a number. */
    public SupportCustomerKeys typedPhoneKeys(String typed) {
        return new SupportCustomerKeys(
                com.innbucks.userservice.util.MsisdnValidator.normalizeToE164(typed, country).stream().toList(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null);
    }

    /** What one read was about: the query as typed (kind + masked form) and the target, if any. */
    public record Subject(String queryKind, String queryMasked, String target) {
        public static Subject phone(String typed) {
            return new Subject("PHONE", SupportMasking.phone(typed), null);
        }

        /** Stored only when it IS a reference: whatever else was typed into the path stays out of the log. */
        public static Subject reference(String typed) {
            String ref = typed == null ? null : com.innbucks.userservice.devicesecurity.SupportRefs.normalise(typed);
            boolean wellFormed = ref != null && ref.matches("^SEC-[0-9A-Z]{6}$");
            return new Subject("DTX_REFERENCE", wellFormed ? ref : null, null);
        }

        public static Subject target(String target) {
            return new Subject(null, null, target);
        }

        public static Subject none() {
            return new Subject(null, null, null);
        }
    }

    /**
     * Counts, runs and records one read.
     *
     * @param keysOf the customer keys the result reached (logged in full)
     */
    public <T> T read(Authentication auth, String clientIp, String op, Subject subject, Supplier<T> call,
                      Function<T, SupportCustomerKeys> keysOf) {
        SupportAgent agent = agents.resolve(auth);
        limiter.acquire(agent);
        T result;
        try {
            result = call.get();
        } catch (DeviceSecurityException e) {
            metrics.lookup(e.getErrorCode());
            accessLog.bestEffort(row(agent, op, subject, null, e.getErrorCode(), clientIp));
            throw e;
        }
        SupportCustomerKeys keys = keysOf == null ? null : keysOf.apply(result);
        accessLog.required(row(agent, op, subject, keys, "OK", clientIp));
        metrics.lookup("ok");
        return result;
    }

    private SupportAccessLog row(SupportAgent agent, String op, Subject subject, SupportCustomerKeys keys,
                                 String outcome, String clientIp) {
        return SupportAccessLog.builder()
                .createdAt(LocalDateTime.now(clock))
                .op(op)
                .outcome(outcome == null ? "ERROR" : SupportSearchService.truncate(outcome, 64))
                .agentUserUuid(agent.userUuid())
                .agentSubject(SupportSearchService.truncate(agent.subject(), 254))
                .queryKind(subject.queryKind())
                .queryMasked(subject.queryMasked())
                .customerKeys(keys == null ? null : SupportAccessLogWriter.json(keys))
                .sections(InnbucksAppSupportSection.NAME)
                .target(SupportSearchService.truncate(subject.target(), 128))
                .clientIpUntrusted(SupportSearchService.truncate(clientIp, 64))
                .build();
    }
}
