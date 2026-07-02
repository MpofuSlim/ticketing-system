package com.innbucks.userservice.util;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.Optional;

/**
 * Validates and canonicalises phone numbers to E.164 ({@code +<cc><national>})
 * before they're stored on any user record or used as a phone-keyed lookup.
 *
 * <p>Why this exists: user-service historically stored whatever the caller
 * typed — one staff member landed as {@code +263777224008}, another as the bare
 * {@code 782606983} (no country code at all). That inconsistency is both a
 * cosmetic mess on the admin console AND a correctness bug: phone is a lookup
 * key here (login-by-phone, OTP challenge, retry quota, pending registration,
 * the {@code uk_users_phone_country} uniqueness constraint), so two spellings of
 * the same number read as two different customers — duplicate accounts, missed
 * OTPs, a uniqueness guard that doesn't guard. Canonicalising every write AND
 * every phone-keyed read to one E.164 form fixes all of those at once.
 *
 * <p>Deliberately identical in shape + library to booking-service's
 * {@code MsisdnValidator} (this repo duplicates small utils per module rather
 * than sharing a jar — see {@code MsisdnMasking}). Keeping the two byte-for-byte
 * compatible means a number normalised in booking and one normalised here are
 * always the same string, so cross-service joins on phone hold.
 *
 * <p>libphonenumber, not a hand-rolled length table: national-number lengths
 * vary across the InnBucks markets (ZW/KE/ZM 9 digits, ZA/LS/BW/SZ 8, NG up to
 * 10) with country-specific ranges. libphonenumber maintains those rules; a
 * hand table would reject valid customers.
 *
 * <p>Normalisation is idempotent — {@code normalizeToE164("+263782606983", "ZW")}
 * returns {@code "+263782606983"} — so it's safe to apply defensively at every
 * layer (controller, service entry, just-before-persist) without double
 * transformation.
 */
public final class MsisdnValidator {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    private MsisdnValidator() {
    }

    /**
     * Validate {@code raw} and return it in canonical E.164 form
     * ({@code +<cc><national>}), or {@link Optional#empty()} if it isn't a valid
     * phone number.
     *
     * @param raw           the caller-supplied number; may or may not carry a
     *                      leading {@code +}, may carry a national trunk
     *                      {@code 0} ({@code 0782...}).
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
