package com.innbucks.userservice.event;

/**
 * Published by {@code RegistrationRejectionService} inside the transaction that
 * removes a rejected registration; {@code RegistrationRejectionListener} hears
 * it AFTER COMMIT and tells the applicant why.
 *
 * <p>Carries the contact details CAPTURED BEFORE the delete: by the time the
 * listener runs the account and its tenant profile are gone, so there is
 * nothing left to re-read. An AFTER_COMMIT listener also means a rejection that
 * rolled back (its required audit row could not be written, say) tells nobody.
 *
 * @param userId       the id the removed account had — for the log line only
 * @param email        the applicant's email; email is the first channel
 * @param phoneNumber  the applicant's E.164 phone — the WhatsApp fallback
 * @param firstName    how the notice greets them; may be blank
 * @param businessName the business they registered, or null for a personal registration
 * @param reason       the administrator's reason, sent verbatim
 */
public record RegistrationRejected(
        Long userId,
        String email,
        String phoneNumber,
        String firstName,
        String businessName,
        String reason) {
}
