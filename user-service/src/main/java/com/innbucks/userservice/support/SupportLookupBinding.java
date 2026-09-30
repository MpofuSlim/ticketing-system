package com.innbucks.userservice.support;

import com.innbucks.userservice.exception.SupportPolicyException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Binds a detail read or a write to the lookup that justified it (design
 * §3.1.5 step 2, T27). The lookup must:
 * <ol>
 *   <li>be named at all — otherwise {@code 400 lookup_required};</li>
 *   <li>exist, belong to THIS agent and be no older than
 *       {@code SUPPORT_LOOKUP_BINDING_TTL} — otherwise {@code 409 lookup_expired}
 *       ("Search for the customer again."). Unknown, someone else's and stale are
 *       deliberately the same answer: a colleague's lookup id must not become an
 *       oracle for what they looked at;</li>
 *   <li>have returned the section the call is for — otherwise {@code 409
 *       lookup_expired} with {@code reason: section_not_in_lookup} (a reference
 *       search returns only the section that owns the reference);</li>
 *   <li>list the target among that section's results — otherwise {@code 404
 *       target_not_found}. This, not the id format, is what stops an agent
 *       walking ids: an id from outside the lookup is simply not found.</li>
 * </ol>
 */
@Component
public class SupportLookupBinding {

    /** A lookup a call is bound to. */
    public record Bound(String lookupId, SupportCustomerKeys keys, List<String> sections,
                        Map<String, List<String>> targets, LocalDateTime createdAt) {
    }

    private final SupportAccessLogRepository log;
    private final SupportProperties properties;
    private final Clock clock;

    public SupportLookupBinding(SupportAccessLogRepository log, SupportProperties properties,
                                @org.springframework.beans.factory.annotation.Qualifier("supportClock") Clock supportClock) {
        this.log = log;
        this.properties = properties;
        this.clock = supportClock;
    }

    public Bound bind(SupportAgent agent, String lookupId, String section, String target) {
        if (lookupId == null || lookupId.isBlank()) {
            throw SupportPolicyException.lookupRequired();
        }
        String id = lookupId.strip().toUpperCase(Locale.ROOT);
        if (!SupportLookupIds.wellFormed(id)) {
            throw SupportPolicyException.lookupExpired();
        }
        SupportAccessLog row = log.findSearch(id).orElseThrow(SupportPolicyException::lookupExpired);
        if (agent.userUuid() == null || !Objects.equals(row.getAgentUserUuid(), agent.userUuid())) {
            throw SupportPolicyException.lookupExpired();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (row.getCreatedAt().plus(properties.getLookupBindingTtl()).isBefore(now)) {
            throw SupportPolicyException.lookupExpired();
        }
        List<String> sections = row.getSections() == null || row.getSections().isBlank() ? List.of()
                : Arrays.asList(row.getSections().split(","));
        if (!sections.contains(section)) {
            throw SupportPolicyException.lookupMissingSection();
        }
        Map<String, List<String>> targets = SupportAccessLogWriter.targets(row.getSectionTargets());
        if (target != null && !targets.getOrDefault(section, List.of()).contains(target)) {
            throw SupportPolicyException.targetNotFound();
        }
        return new Bound(id, SupportAccessLogWriter.keys(row.getCustomerKeys()), sections, targets, row.getCreatedAt());
    }
}
