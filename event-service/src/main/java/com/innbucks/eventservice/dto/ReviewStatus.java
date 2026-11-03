package com.innbucks.eventservice.dto;

import com.innbucks.eventservice.entity.Event;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where an event stands in the review step (V17). Derived, never stored, so it
 * cannot disagree with the flags it is read from:
 * <ol>
 *   <li>{@code rejected}: {@link #REJECTED};</li>
 *   <li>{@code approvedAt} set: {@link #APPROVED};</li>
 *   <li>{@code submittedAt} set: {@link #SUBMITTED};</li>
 *   <li>otherwise {@link #DRAFT}.</li>
 * </ol>
 * Whether the event is visible is still {@code active}: an APPROVED event its
 * organizer has unpublished is APPROVED and inactive.
 */
@Schema(description = """
        Where the event stands in review. DRAFT: not yet submitted. SUBMITTED: awaiting a staff decision.
        APPROVED: allowed to go live (whether it is live now is `active`). REJECTED: refused, with
        rejectionReason; the organizer can edit and publish again to resubmit (review step on).""",
        example = "SUBMITTED")
public enum ReviewStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED;

    public static ReviewStatus of(Event event) {
        if (event.isRejected()) {
            return REJECTED;
        }
        if (event.getApprovedAt() != null) {
            return APPROVED;
        }
        return event.getSubmittedAt() != null ? SUBMITTED : DRAFT;
    }
}
