package com.innbucks.userservice.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * An invite was minted and must be emailed once the transaction commits (V44).
 * Handled by {@code StaffInviteMailer} ({@code AFTER_COMMIT} + {@code @Async}),
 * so a rolled-back create never sends a link to an account that does not exist.
 *
 * <p>Carries the RAW token — the only place it exists outside the email — so
 * {@link #toString()} is overridden to leave it out: an event logged by a
 * framework or a debugger must never print it.
 *
 * @param inviteId      {@code staff_invites.id}; the delivery reference is
 *                      {@code STAFF-INVITE-<inviteId>}
 * @param to            the address the invite is bound to
 * @param firstName     greeting
 * @param rawToken      the {@code STI-} token for the link — never logged
 * @param roles         the roles the account holds, for the copy
 * @param invitedBy     who created or re-sent it, for the copy
 * @param expiresAt     UTC; rendered in market time in the copy
 */
public record StaffInviteRequested(Long inviteId, String to, String firstName, String rawToken,
                                   List<String> roles, String invitedBy, LocalDateTime expiresAt) {

    @Override
    public String toString() {
        return "StaffInviteRequested[inviteId=" + inviteId + ", token=<redacted>]";
    }
}
