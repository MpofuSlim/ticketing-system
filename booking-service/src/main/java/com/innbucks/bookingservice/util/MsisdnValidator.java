package com.innbucks.bookingservice.util;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.Optional;

/**
 * Validates and canonicalises customer phone numbers before they're stored on
 * a booking and later handed to the WhatsApp gateway.
 *
 * <p>Why this exists: a malformed MSISDN (e.g. a Zimbabwe number missing a
 * digit — {@code +26378260983} instead of {@code +263782606983}) sails through
 * a naive "starts with + and is mostly digits" check, gets stored, and only
 * fails hours later at Twilio with error 63024 ("invalid message recipient") —
 * by which point the booking is confirmed but the e-ticket never arrives.
 * Catching it at creation turns a silent delivery failure into an immediate,
 * actionable 400.
 *
 * <p>We use Google's libphonenumber rather than a hand-rolled per-country
 * length table because national number lengths vary across the InnBucks markets
 * (ZW/KE/ZM 9 digits, ZA/LS/BW/SZ 8, NG up to 10) and have country-specific
 * ranges; encoding that by hand risks rejecting valid customers. libphonenumber
 * maintains those rules for every country.
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
