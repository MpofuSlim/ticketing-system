package com.innbucks.bookingservice.util;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves an E.164 MSISDN (e.g. {@code +263782606983}) to the ISO 3166-1
 * alpha-2 country code of its dialling prefix (e.g. {@code ZW}). Used by
 * {@code BookingConfirmedNotificationListener} to decide whether to route a
 * confirmation message through the per-country SMS gateway (domestic) or
 * straight to WhatsApp (foreign) — the InnBucks SMS gateways are
 * single-country and silently drop foreign MSISDNs after a 2xx.
 *
 * <p>This is the same resolver user-service ships under its own package;
 * services don't share code in this fleet, so the implementation is
 * duplicated rather than depended on. Keep the two in lockstep when adding
 * new markets — the prefix table is the source of truth on both sides.
 *
 * <p>Scope: the ten InnBucks target markets. Adding a market is a one-line
 * entry in the prefix table. An unknown prefix returns {@link Optional#empty()}
 * — never a fallback country, because guessing here is how customers end up
 * on the wrong gateway.
 */
public final class MsisdnCountryResolver {

    private static final Map<String, String> PREFIX_TO_ISO = Map.ofEntries(
            Map.entry("234", "NG"),   // Nigeria
            Map.entry("254", "KE"),   // Kenya
            Map.entry("258", "MZ"),   // Mozambique
            Map.entry("260", "ZM"),   // Zambia
            Map.entry("263", "ZW"),   // Zimbabwe — current home market
            Map.entry("265", "MW"),   // Malawi
            Map.entry("266", "LS"),   // Lesotho
            Map.entry("267", "BW"),   // Botswana
            Map.entry("268", "SZ"),   // Eswatini
            // 2-digit prefix (South Africa). resolve() prefers a 3-digit match.
            Map.entry("27",  "ZA")
    );

    private MsisdnCountryResolver() {
    }

    /**
     * Resolve the country of an E.164 MSISDN. Accepts the leading {@code +}
     * or its absence. Returns empty when the input is blank, contains
     * non-digit characters, or starts with a prefix not in the InnBucks
     * markets table. Longest-prefix-first.
     */
    public static Optional<String> resolve(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return Optional.empty();
        }
        String digits = msisdn.startsWith("+") ? msisdn.substring(1) : msisdn;
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        if (digits.length() >= 3) {
            String iso = PREFIX_TO_ISO.get(digits.substring(0, 3));
            if (iso != null) {
                return Optional.of(iso);
            }
        }
        if (digits.length() >= 2) {
            String iso = PREFIX_TO_ISO.get(digits.substring(0, 2));
            if (iso != null) {
                return Optional.of(iso);
            }
        }
        return Optional.empty();
    }
}
