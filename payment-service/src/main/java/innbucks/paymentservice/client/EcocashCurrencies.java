package innbucks.paymentservice.client;

import java.util.Locale;
import java.util.Set;

/**
 * The currencies the EcoCash EIP rail accepts, and the one place that decides
 * whether a currency may reach the wire.
 *
 * <h2>Why this exists — the failure it prevents</h2>
 * Measured against preprod on 2026-09-08: sending an unsupported
 * {@code currencyCode} does <b>not</b> come back as a business rejection. It
 * comes back as EcoCash's edge WAF page — {@code text/html}, "Request Rejected
 * / Your support ID is …" — reproducibly (tested {@code 710} and {@code 999},
 * 60s apart, distinct support IDs).
 *
 * <p>{@link EcocashEipClient} correctly classifies a non-JSON body as
 * INFRASTRUCTURE and raises it as transient, never as a status — that rule is
 * right and must stay. But it means a <b>permanent configuration error is
 * indistinguishable from an outage</b>: the row stays {@code TOKEN_ISSUED}, the
 * poller retries it forever, and it holds the order's ONLY payment slot across
 * all three rails. The customer can then never pay for that order by any
 * method. Validating here turns that silent, unrecoverable pin into a clean
 * refusal before any ledger row is opened.
 *
 * <h2>Why ALPHA-3 only, and why the set is code not config</h2>
 * EIP accepts the alpha code ({@code "USD"} / {@code "ZWG"}). A numeric ISO
 * code is a second, quieter trap: preprod showed the <b>charge</b> response
 * echoes what you sent verbatim ({@code "840"}), while the <b>query</b> — the
 * response {@code EcocashPaymentService.echoMismatch} actually runs on —
 * normalises it to {@code "USD"}. Since the ledger currency and the wire
 * currency are the same string, a numeric cell currency would make every
 * COMPLETED payment fail the echo check and park IN_DOUBT: no error, no boot
 * warning, 100% of payments stuck in an operator queue.
 *
 * <p>So the set is a hardcoded allow-list, deliberately — the currency analogue
 * of loyalty's {@code SupportedCurrencies} and {@code KNOWN_COUNTRIES}. Making
 * it env-configurable would reintroduce exactly the footgun it closes, because
 * an operator could set {@code 840} and be back to a silently pinned rail.
 * Adding a currency EcoCash later supports is a one-line, reviewed code change.
 */
public final class EcocashCurrencies {

    /**
     * Alpha-3 codes EIP accepts. USD and ZWG are the two named in EcoCash's own
     * test data ({@code USD/840}, {@code ZWG/924}) and in the V3 API doc's
     * {@code currencyCode} field description.
     *
     * <p><b>Only ZWG is unmeasured on the wire.</b> Every preprod capture we
     * hold is USD, so we have not confirmed that a ZWG query echoes {@code
     * "ZWG"} byte-for-byte back. Before this cell transacts ZWG on this rail,
     * run one charge + query in ZWG and confirm the echo matches the ledger
     * string — otherwise the echo guard will park those rows IN_DOUBT.
     */
    public static final Set<String> SUPPORTED = Set.of("USD", "ZWG");

    private EcocashCurrencies() {}

    /**
     * Trimmed, upper-cased form, or {@code null} for a null/blank input.
     *
     * <p>Callers should store and send THIS value, so the string on the ledger
     * is byte-identical to the string on the wire — the invariant the echo
     * guard rests on.
     */
    public static String canonical(String currency) {
        if (currency == null) {
            return null;
        }
        String trimmed = currency.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /** True when this currency may reach the EcoCash wire. Fails closed on null/blank. */
    public static boolean isSupported(String currency) {
        String canonical = canonical(currency);
        return canonical != null && SUPPORTED.contains(canonical);
    }
}
