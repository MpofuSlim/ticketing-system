package com.innbucks.userservice.event;

import java.time.LocalDateTime;

/**
 * A sales-agent portal invite was minted and must be emailed once the
 * transaction commits (V56). Handled by {@code SalesAgentInviteMailer}
 * ({@code AFTER_COMMIT} + {@code @Async}), so a rolled-back invite sends nothing.
 *
 * <p>Carries the RAW token, the only place it exists outside the email, so
 * {@link #toString()} leaves it out: an event logged by a framework or a
 * debugger must never print it.
 *
 * @param inviteId      {@code sales_agent_invites.id}; the delivery reference is
 *                      {@code SALES-AGENT-INVITE-<inviteId>}
 * @param to            the address the invite is bound to
 * @param organizerName who invited them, for the copy (may be null)
 * @param rawToken      the {@code SAI-} token for the link; never logged
 * @param expiresAt     UTC; rendered in market time in the copy
 */
public record SalesAgentInviteRequested(Long inviteId, String to, String organizerName, String rawToken,
                                        LocalDateTime expiresAt) {

    @Override
    public String toString() {
        return "SalesAgentInviteRequested[inviteId=" + inviteId + ", token=<redacted>]";
    }
}
