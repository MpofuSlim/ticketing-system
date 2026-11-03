package com.innbucks.eventservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether an organizer's publish goes through staff review on this cell
 * ({@code events.review.required}, env {@code EVENTS_REVIEW_REQUIRED}, default
 * false). A rollout device like the ticket-number mask: switch it on per cell
 * once the Foundry portal shows "Awaiting approval" and the staff review queue.
 *
 * <ul>
 *   <li><b>Off:</b> the organizer publishes as before. The first time an event
 *       goes live, the review staff are told (EVENT_PUBLISHED) so they can
 *       look and reject it.</li>
 *   <li><b>On:</b> the organizer's publish submits the event for review
 *       (EVENT_SUBMITTED to the staff) and it goes live when SUPER_ADMIN or
 *       PRODUCT_MANAGER approves it. Rejecting needs a reason.</li>
 * </ul>
 * Either way an event that has been approved once is never reviewed again
 * (owner decision, 2026-11-03): its organizer edits, unpublishes and
 * republishes it freely, and staff can still reject it at any time.
 */
@Component
@Slf4j
public class EventReviewPolicy {

    private final boolean required;

    public EventReviewPolicy(@Value("${events.review.required:false}") boolean required) {
        this.required = required;
        log.info("Event review step is {}", required
                ? "ON: an organizer's publish submits the event for staff approval"
                : "OFF: organizers publish directly and the review staff are told");
    }

    public boolean required() {
        return required;
    }
}
