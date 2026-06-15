package com.innbucks.userservice.util;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves an E.164 MSISDN (e.g. {@code +263782606983}) to the ISO 3166-1
 * alpha-2 country code of its dialling prefix (e.g. {@code ZW}). Used by
 * {@code JwtUtil} to stamp a {@code homeCountry} claim on customer tokens at
 * mint time — the routing signal the eventual per-country cell architecture
 * keys off without needing a central directory of who-lives-where.
 *
 * <p>Why ISO codes and not the existing {@code country} text claim
 * ({@code "Zimbabwe"}, {@code "South Africa"}): ISO codes are a tiny, fixed
 * alphabet — no case-sensitivity, no language, no typos, safe to compare and
 * index. The legacy {@code country} claim stays untouched (it's account
 * metadata sourced from {@code users.country}); {@code homeCountry} is a
 * purpose-built routing key sourced from the customer's phone number.
 *
 * <p>Scope: the ten InnBucks target markets. Adding a market is a one-line
 * entry in the prefix table. An unknown prefix returns {@link Optional#empty()}
 * — never a fallback country, because guessing a country on the auth path is
 * how customers end up in the wrong cell.
 *
 * <p>Stateless utility, mirrors {@link MsisdnMasking}'s shape so the
 * security package stays consistent.
 */
public final class MsisdnCountryResolver {

    /**
     * Dialling-prefix → ISO 3166-1 alpha-2. Strings, not enums, so an
     * unknown prefix added by mistake doesn't blow up enum parsing
     * downstream — the resolver just returns empty.
     *
     * <p>Africa codes are mostly 3-digit ({@code 234..268}); South Africa
     * is the lone 2-digit outlier ({@code 27}). {@link #resolve(String)}
     * matches longest-prefix-first to handle the overlap safely.
     */
    private static final Map<String, String> PREFIX_TO_ISO = Map.ofEntries(
            // 3-digit prefixes — the bulk of the 10 markets.
            Map.entry("234", "NG"),   // Nigeria
            Map.entry("254", "KE"),   // Kenya
            Map.entry("258", "MZ"),   // Mozambique
            Map.entry("260", "ZM"),   // Zambia
            Map.entry("263", "ZW"),   // Zimbabwe — current home market
            Map.entry("265", "MW"),   // Malawi
            Map.entry("266", "LS"),   // Lesotho
            Map.entry("267", "BW"),   // Botswana
            Map.entry("268", "SZ"),   // Eswatini
            // 2-digit prefix — South Africa. Stored separately so the
            // longest-prefix-first match in resolve() picks the 3-digit
            // entry whenever both could match.
            Map.entry("27",  "ZA")    // South Africa
    );

    /**
     * ISO 3166-1 alpha-2 → display country name, for user-facing copy.
     * Keep in lockstep with {@link #PREFIX_TO_ISO} above (same 10 markets).
     * Used by error messages so a customer sees "Kenya" instead of "KE".
     */
    private static final Map<String, String> ISO_TO_NAME = Map.ofEntries(
            Map.entry("NG", "Nigeria"),
            Map.entry("KE", "Kenya"),
            Map.entry("MZ", "Mozambique"),
            Map.entry("ZM", "Zambia"),
            Map.entry("ZW", "Zimbabwe"),
            Map.entry("MW", "Malawi"),
            Map.entry("LS", "Lesotho"),
            Map.entry("BW", "Botswana"),
            Map.entry("SZ", "Eswatini"),
            Map.entry("ZA", "South Africa")
    );

    private MsisdnCountryResolver() {
    }

    /**
     * Display name for an ISO 3166-1 alpha-2 code (e.g. {@code "KE"} →
     * {@code "Kenya"}). Returns the input back when the code is unknown
     * or blank — better to show a recognisable token than nothing. Use for
     * user-facing copy only; routing keys off the ISO code.
     */
    public static String countryName(String iso) {
        if (iso == null || iso.isBlank()) return "";
        String name = ISO_TO_NAME.get(iso.trim().toUpperCase());
        return name != null ? name : iso;
    }

    /**
     * Resolve the country of an E.164 MSISDN. Accepts the leading {@code +}
     * or its absence ({@code "+263..."} and {@code "263..."} both work).
     * Returns {@link Optional#empty()} when the input is blank, contains
     * non-digit characters after the optional {@code +}, or starts with a
     * prefix not in the InnBucks markets table.
     *
     * <p>Longest-prefix-first: a 3-digit match wins over a 2-digit one.
     * Without this, a hypothetical 2-digit prefix shared with the start of
     * a 3-digit one would mis-route — defensive even though no current
     * overlaps exist in the table.
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
