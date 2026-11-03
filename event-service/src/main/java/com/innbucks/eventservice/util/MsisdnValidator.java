package com.innbucks.eventservice.util;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.Optional;

/**
 * Validates and canonicalises phone numbers to E.164 ({@code +<cc><national>}):
 * a super-app scanner's number (V14), so one number has one spelling whatever
 * the organizer typed, and matches the phone on the scanner's own session.
 *
 * <p>A copy of booking-service's and user-service's (the services share no
 * code). libphonenumber rather than a hand-rolled length table, because
 * national number lengths and ranges differ across the InnBucks markets.
 */
public final class MsisdnValidator {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    private MsisdnValidator() {
    }

    /**
     * Validate {@code raw} and return it in canonical E.164 form
     * ({@code +<cc><national>}), or {@link Optional#empty()} if it isn't a
     * valid phone number.
     *
     * @param raw           the customer-supplied number; may or may not carry a
     *                      leading {@code +}.
     * @param defaultRegion ISO 3166-1 alpha-2 region used to interpret a number
     *                      that lacks a {@code +} country code (the deployment's
     *                      cell country, e.g. {@code "ZW"}). Numbers that DO
     *                      carry a {@code +} are parsed by their own country
     *                      code and the default region is ignored.
     */
    public static Optional<String> normalizeToE164(String raw, String defaultRegion) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Phonenumber.PhoneNumber parsed = PHONE_UTIL.parse(raw.trim(), defaultRegion);
            if (!PHONE_UTIL.isValidNumber(parsed)) {
                return Optional.empty();
            }
            return Optional.of(PHONE_UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    /** True when {@code raw} is a valid phone number under {@code defaultRegion}. */
    public static boolean isValid(String raw, String defaultRegion) {
        return normalizeToE164(raw, defaultRegion).isPresent();
    }
}
