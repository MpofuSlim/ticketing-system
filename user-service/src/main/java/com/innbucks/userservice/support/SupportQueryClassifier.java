package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.SupportRefs;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.util.MsisdnValidator;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What an agent typed into the one search box, and what that lets them see
 * (design §3.1.1). Pure: no I/O, no logging — the caller records the KIND of a
 * refused query, never its text.
 *
 * <p>Input is stripped. For references, {@code s} = the input with spaces removed
 * and upper-cased, and {@code d} = {@code s} with dashes and brackets also
 * removed. Every pattern is ANCHORED, and the rows are tried in order:
 *
 * <ol>
 *   <li>contains {@code @} → EMAIL (lower-cased)</li>
 *   <li>{@code ^SEC-?[0-9A-Z]{6}$} on {@code s} → DTX support reference. The prefix
 *       is confirmed HERE, before {@link SupportRefs#normalise}, because that
 *       helper prepends {@code SEC-} to anything (T15)</li>
 *   <li>{@code ^MKT-?[0-9A-F]{12}$} → marketplace order</li>
 *   <li>{@code ^TRK-?[0-9A-Z]{10}$} → parcel (the prefix is REQUIRED)</li>
 *   <li>{@code ^VCH-?[0-9A-F]{12}$} → voucher purchase order (same body shape as
 *       MKT — only the prefix tells them apart, T15)</li>
 *   <li>{@code ^INN-\d{8}-[0-9A-F]{6}$} → booking confirmation</li>
 *   <li>{@code ^\d{8}-\d{5}[A-Z]$} → ticket number</li>
 *   <li>{@code ^TKZ-[A-Z]{3,12}-[0-9A-F]{12}$} / {@code ^TKT-PMT-<uuid>$} → payment reference</li>
 *   <li>REFUSED {@code query_not_accepted}: {@code d} is 13–19 digits (a card or
 *       voucher number — also the shape marketplace's PHONE_SHAPE would take for
 *       a phone, T15), or {@code ^(?=.*[A-Z])[0-9A-Z]{12}$} with no recognised
 *       prefix (a legacy voucher code or a collection code)</li>
 *   <li>{@link MsisdnValidator#normalizeToE164} → PHONE</li>
 *   <li>REFUSED {@code query_not_recognised}</li>
 * </ol>
 *
 * <p>Rows 3–8 name references whose product section is not built yet (PRs 3–5):
 * they classify — so a card-shaped or code-shaped input can never be mistaken
 * for one — and are then answered {@code 400 query_not_supported} by the search.
 */
public final class SupportQueryClassifier {

    /** What the query is. REFUSED kinds carry no value. */
    public enum Kind {
        EMAIL(true, null),
        DTX_REFERENCE(true, PermissionCatalog.DEVICE_SECURITY_READ),
        MARKETPLACE_ORDER(false, "support-marketplace:read"),
        PARCEL(false, "support-marketplace:read"),
        VOUCHER_ORDER(false, "support-loyalty:read"),
        BOOKING_CONFIRMATION(false, "support-ticketing:read"),
        TICKET_NUMBER(false, "support-ticketing:read"),
        PAYMENT_REFERENCE(false, null),
        PHONE(true, null),
        /** Row 9: a card, voucher or collection code. */
        NOT_ACCEPTED(false, null),
        /** Row 11. */
        NOT_RECOGNISED(false, null);

        private final boolean supported;
        private final String permission;

        Kind(boolean supported, String permission) {
            this.supported = supported;
            this.permission = permission;
        }

        /** True for the kinds this build can search by. */
        public boolean supported() {
            return supported;
        }

        /**
         * The section read permission a REFERENCE kind needs; null for EMAIL and
         * PHONE (any section read) and for the refusals. Named as literals for
         * the unbuilt sections: those codes enter the catalog with the PR that
         * enforces them (§3.3), and nothing here grants or checks them yet.
         */
        public String permission() {
            return permission;
        }

        public boolean isReference() {
            return this != EMAIL && this != PHONE && this != NOT_ACCEPTED && this != NOT_RECOGNISED;
        }
    }

    /** The classification: the kind, and the canonical value (null for a refusal). */
    public record Query(Kind kind, String value) {
    }

    private static final Pattern SEC = Pattern.compile("^SEC-?[0-9A-Z]{6}$");
    private static final Pattern MKT = Pattern.compile("^MKT-?[0-9A-F]{12}$");
    private static final Pattern TRK = Pattern.compile("^TRK-?[0-9A-Z]{10}$");
    private static final Pattern VCH = Pattern.compile("^VCH-?[0-9A-F]{12}$");
    private static final Pattern INN = Pattern.compile("^INN-\\d{8}-[0-9A-F]{6}$");
    private static final Pattern TICKET = Pattern.compile("^\\d{8}-\\d{5}[A-Z]$");
    private static final Pattern TKZ = Pattern.compile("^TKZ-[A-Z]{3,12}-[0-9A-F]{12}$");
    private static final Pattern TKT_PMT = Pattern.compile(
            "^TKT-PMT-[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$");
    private static final Pattern CARD_DIGITS = Pattern.compile("^\\d{13,19}$");
    private static final Pattern CODE_12 = Pattern.compile("^(?=.*[A-Z])[0-9A-Z]{12}$");
    /** Dashes and brackets, removed to form {@code d}. */
    private static final Pattern DASHES_BRACKETS = Pattern.compile("[-()\\[\\]\\u2010-\\u2015\\u2212]");

    private final String deploymentCountry;

    public SupportQueryClassifier(String deploymentCountry) {
        this.deploymentCountry = deploymentCountry == null || deploymentCountry.isBlank() ? "ZW" : deploymentCountry;
    }

    public Query classify(String raw) {
        String input = raw == null ? "" : raw.strip();
        if (input.isEmpty()) return new Query(Kind.NOT_RECOGNISED, null);

        // Row 1.
        if (input.indexOf('@') >= 0) {
            return new Query(Kind.EMAIL, input.toLowerCase(Locale.ROOT));
        }

        String s = input.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        String d = DASHES_BRACKETS.matcher(s).replaceAll("");
        // The international call prefix: 00263 77 123 4567 is +263 77 123 4567,
        // not a 14-digit card number. Rewritten BEFORE row 9 — no card number
        // (the ISO 7812 issuer ranges start at 1) and no voucher code (first
        // digit 1-9) begins with 00, so nothing that row exists to refuse is let
        // through by it.
        if (d.startsWith("00") && d.length() > 2 && d.chars().allMatch(Character::isDigit)) {
            d = "+" + d.substring(2);
            input = d;
        }

        // Row 2: the SEC- prefix is proven on s BEFORE SupportRefs.normalise.
        if (SEC.matcher(s).matches()) {
            return new Query(Kind.DTX_REFERENCE, SupportRefs.normalise(s));
        }
        // Rows 3–8: classified so they can never fall through to a looser row.
        if (MKT.matcher(s).matches()) return new Query(Kind.MARKETPLACE_ORDER, withDash(s, "MKT"));
        if (TRK.matcher(s).matches()) return new Query(Kind.PARCEL, withDash(s, "TRK"));
        if (VCH.matcher(s).matches()) return new Query(Kind.VOUCHER_ORDER, withDash(s, "VCH"));
        if (INN.matcher(s).matches()) return new Query(Kind.BOOKING_CONFIRMATION, s);
        if (TICKET.matcher(s).matches()) return new Query(Kind.TICKET_NUMBER, s);
        if (TKZ.matcher(s).matches() || TKT_PMT.matcher(s).matches()) {
            return new Query(Kind.PAYMENT_REFERENCE, s);
        }

        // Row 9: never searchable, whatever else they might resemble.
        if (CARD_DIGITS.matcher(d).matches() || CODE_12.matcher(d).matches()) {
            return new Query(Kind.NOT_ACCEPTED, null);
        }

        // Row 10.
        Optional<String> phone = MsisdnValidator.normalizeToE164(input, deploymentCountry);
        if (phone.isPresent()) return new Query(Kind.PHONE, phone.get());

        // Row 11.
        return new Query(Kind.NOT_RECOGNISED, null);
    }

    /**
     * The marketplace tag of a {@code TKZ-} payment reference decides its owning
     * section ({@code MKT} → marketplace, otherwise ticketing). The tag
     * vocabulary is an assumption to verify when PR 3/5 builds those sections;
     * until then every payment reference is {@code query_not_supported}.
     */
    public static boolean isMarketplacePaymentRef(String value) {
        return value != null && value.startsWith("TKZ-MKT-");
    }

    private static String withDash(String s, String prefix) {
        return s.startsWith(prefix + "-") ? s : prefix + "-" + s.substring(prefix.length());
    }
}
