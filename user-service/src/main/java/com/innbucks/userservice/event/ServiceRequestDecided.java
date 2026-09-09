package com.innbucks.userservice.event;

/**
 * Published by {@code ServiceRequestService} once a SUPER_ADMIN's decision on a
 * service-bundle request is persisted. {@code ServiceRequestDecisionListener}
 * hears it post-commit and tells the requester.
 *
 * <p><b>Why an event rather than an inline send.</b> Before this, an approval
 * told the requester nothing at all — they discovered it by revisiting the app
 * and noticing a new menu item. Sending inline would put an outbound HTTP call
 * inside the admin's transaction, and worse, would fire on a transaction that
 * later rolled back: the requester would be told their request was approved
 * when it was not. AFTER_COMMIT is the fleet's standing discipline for exactly
 * that reason.
 *
 * <p>Carries everything the listener needs so it never re-reads the request or
 * the user.
 */
public record ServiceRequestDecided(
        Long requestId,
        Long userId,
        /** Requester's stable user_uuid — how the in-app notification is addressed. */
        java.util.UUID userUuid,
        /** Requester's email; may be null for an account with none on file. */
        String email,
        /** Requester's MSISDN — the WhatsApp fallback when email fails. */
        String phoneNumber,
        /** Bundle that was requested, e.g. {@code marketplace}. */
        String service,
        Outcome outcome,
        /** The reviewer's stated reason. Always set on a REJECTED, optional otherwise. */
        String decisionReason
) {
    public enum Outcome { APPROVED, REJECTED }
}
