package innbucks.paymentservice.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    /**
     * {@code PROCESSING} is additive (the historical stub only ever emitted
     * SUCCESS): the normal first response of the 2D-code flow — an InnBucks
     * payment code was issued and sent to the customer's phone; the booking
     * confirms automatically once they approve it. FE treatment: same as
     * SUCCESS visually ("payment received — approve in your InnBucks app");
     * the confirmation number lands on the booking.
     */
    public enum Status { SUCCESS, PROCESSING, FAILED }

    /**
     * Additive, machine-readable refinement of {@link Status}. {@code status}
     * is the historical coarse contract and stays exactly as it was —
     * {@code PROCESSING} covers six distinct ledger states, and until this
     * field existed the ONLY way to tell them apart was reading the
     * human-facing {@code message} prose. Prose is not a contract: it is
     * localisable, reworded freely, and must never be branched on.
     *
     * <p>Clients should switch on {@code stage}. New values may be added;
     * none will be removed. For an unrecognised value, do <b>not</b> map it
     * onto a known stage — every known stage carries a money claim, so
     * guessing imports a claim you have no basis for. Read
     * {@code fundsCaptured} instead and treat {@code null} as unknown; UI-wise
     * an unrecognised stage is "we're still working on this", never "you were
     * not charged".
     */
    public enum Stage {
        /**
         * A payment instrument is live and renderable — the customer must act
         * (enter card details, or approve the InnBucks code). NOTHING has been
         * charged. Render the payment UI.
         */
        AWAITING_PAYMENT,
        /**
         * The request is in flight server-side; no instrument exists yet.
         * Nothing charged. Show a spinner and retry shortly.
         */
        IN_PROGRESS,
        /**
         * Our LOCAL deadline for the instrument has passed and the ledger has
         * NOT yet concluded what happened upstream.
         *
         * <p>Read that carefully: this is not "confirmed unpaid". A row only
         * leaves this state as positively-unpaid when the gateway SAYS so
         * (code rail: Expired/Timed Out; card rail: {@code 200.300.404} past
         * the 30-minute ceiling), and at that point it becomes terminal
         * EXPIRED and stops being replayed at all. So every row a client
         * actually sees here is one the reconciler has not resolved —
         * usually for a few seconds, but indefinitely when the upstream
         * status is unreadable, which is exactly the case the poller refuses
         * to guess about ("the customer may have paid").
         *
         * <p>Hence {@code fundsCaptured} is {@code null}, not {@code false}.
         * The stage is still useful for UI ("that took too long — we're
         * checking"), but do NOT tell the customer they were not charged, and
         * note the order's payment slot is still HELD: re-POSTing returns this
         * same state rather than minting a fresh instrument, until the
         * reconciler resolves the row.
         */
        INSTRUMENT_EXPIRED,
        /**
         * We cannot present a payment instrument right now (deployment
         * misconfiguration or gateway outage). Nothing charged. Retrying
         * returns this same state until an operator fixes it, so show the
         * message rather than an automatic retry loop.
         */
        PAYMENT_UNAVAILABLE,
        /**
         * <b>Money HAS been captured</b> and the order is being confirmed.
         * This is the state that deserves a confident "payment received,
         * confirming your booking…" screen. Confirmation normally lands
         * within seconds; keep polling the order.
         */
        PAYMENT_RECEIVED,
        /** Money captured AND the order confirmed. Terminal; pairs with {@link Status#SUCCESS}. */
        COMPLETED,
        /**
         * The outcome is genuinely UNKNOWN to us — the upstream answer was
         * unreadable or contradicted our ledger, and the row is parked for an
         * operator. Money may or may not have moved, so
         * {@code fundsCaptured} is {@code null}. Do NOT claim either outcome
         * to the customer; direct them to support.
         */
        VERIFYING;

        /**
         * The money question, answered from the stage alone — the ONE place
         * the mapping lives.
         *
         * <p>Only two stages assert capture, and only ONE asserts non-capture
         * on the strength of an upstream verdict. Everything whose upstream
         * outcome we have not positively established answers {@code null}:
         * {@code false} is a claim ("you were not charged") and this service's
         * standing rule is that an unresolved payment is never guessed in
         * either direction.
         *
         * <p>The switch is deliberately EXHAUSTIVE with no {@code default}
         * arm: adding a stage without deciding its money answer is then a
         * COMPILE error, which is a stronger guarantee than any runtime
         * fallback could give.
         */
        public Boolean fundsCaptured() {
            return switch (this) {
                // Money provably moved.
                case PAYMENT_RECEIVED, COMPLETED -> Boolean.TRUE;
                // Money provably did NOT move: no instrument has been
                // presented to the customer yet (IN_PROGRESS), or one is
                // live and untouched (AWAITING_PAYMENT), or we never managed
                // to present one at all (PAYMENT_UNAVAILABLE).
                case AWAITING_PAYMENT, IN_PROGRESS, PAYMENT_UNAVAILABLE -> Boolean.FALSE;
                // Genuinely unknown. INSTRUMENT_EXPIRED belongs here, not
                // with the FALSE group: a client only ever sees it while the
                // reconciler has NOT concluded the instrument went unpaid
                // (see its javadoc), and the poller explicitly refuses to
                // resolve unreadable rows because the customer may have paid.
                case INSTRUMENT_EXPIRED, VERIFYING -> null;
            };
        }
    }

    private UUID transactionId;

    /**
     * Legacy echo, populated for BOOKING payments only (the historical stub
     * contract). MARKETPLACE payments carry {@code null} here and identify
     * the order via the additive {@link #orderType}/{@link #orderRef} pair.
     */
    private UUID bookingId;

    /** Additive: which product this payment collects for (BOOKING / MARKETPLACE). */
    private innbucks.paymentservice.order.OrderType orderType;

    /** Additive: the product-side order reference (booking UUID text / MKT-... ref). */
    private String orderRef;

    private Status status;

    /**
     * Additive discriminator — see {@link Stage}. Always populated.
     */
    @io.swagger.v3.oas.annotations.media.Schema(
            description = "Machine-readable refinement of `status`, which collapses six situations "
                    + "into PROCESSING. Branch on THIS, never on `message` (prose, reworded and "
                    + "localised). For an unrecognised value do NOT assume a money answer — read "
                    + "`fundsCaptured` and treat null as unknown.",
            example = "AWAITING_PAYMENT")
    private Stage stage;

    /**
     * <b>Has money actually left the customer?</b> Three honest answers, only
     * two of which are booleans:
     *
     * <ul>
     *   <li>{@code true}  — captured ({@link Stage#PAYMENT_RECEIVED},
     *       {@link Stage#COMPLETED}).</li>
     *   <li>{@code false} — provably not captured (no instrument presented
     *       yet, one live and untouched, or none presentable at all).</li>
     *   <li>{@code null}  — <b>UNKNOWN</b> ({@link Stage#VERIFYING},
     *       {@link Stage#INSTRUMENT_EXPIRED}). Returning {@code false} here
     *       would tell a customer who may have paid that nothing was charged;
     *       {@code true} would promise a ticket we cannot back. Treat null as
     *       "don't claim either way".</li>
     * </ul>
     *
     * <p>This is a DERIVED, read-only property with no backing field: there
     * is deliberately no setter and no builder method, so it cannot be set to
     * something that contradicts {@link #stage}. (An earlier version stored it
     * as a field and derived it inside the builder — but Lombok still
     * generated a public {@code fundsCaptured(...)} builder method, which made
     * the two order-dependently contradictable. Having no field at all is the
     * only version of "cannot drift" that is actually true.)
     *
     * <p><b>{@code ALWAYS} is load-bearing, not decoration.</b> Several sibling
     * DTOs in this package carry {@code @JsonInclude(NON_NULL)} at class level,
     * so adding it here later would be the natural-looking change — and it
     * would silently delete the key for exactly the unknown cases, leaving a
     * client unable to distinguish "we don't know" from "old server that
     * doesn't send this field". Member-level inclusion beats class-level, so
     * this survives that edit. {@code PaymentResponseSerializationTest} fails
     * if the key ever goes missing.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    @io.swagger.v3.oas.annotations.media.Schema(
            nullable = true,
            description = "Has money actually left the customer? THREE-STATE: true = captured, "
                    + "false = provably not captured, null = UNKNOWN (stage VERIFYING or "
                    + "INSTRUMENT_EXPIRED). null is a real answer, not a missing value — never "
                    + "coerce it to false, which would tell a customer who may have paid that "
                    + "nothing was charged.",
            example = "false")
    public Boolean getFundsCaptured() {
        return stage == null ? null : stage.fundsCaptured();
    }

    private BigDecimal amountPaid;
    private String currency;
    private String confirmationNumber;
    private LocalDateTime processedAt;

    /**
     * The InnBucks code the customer approves, its approval deadline, and
     * the InnBucks-rendered QR image (base64). Present while
     * {@code status=PROCESSING}; the FE renders both code and QR on the
     * checkout screen (the response IS the delivery — no out-of-band
     * messaging). QR render: {@code data:image/png;base64,<paymentQrCode>}.
     * Deep link: {@code com.innbucks.customer://purchase?paymentToken=<code>}.
     */
    private String paymentCode;
    private LocalDateTime paymentCodeExpiresAt;
    private String paymentQrCode;

    /**
     * Additive: which rail this payment runs on ({@code INNBUCKS_CODE} —
     * render the code/QR fields above — or {@code ZIMSWITCH_CARD} — render
     * the COPYandPAY widget from the checkout fields below).
     */
    private innbucks.paymentservice.entity.PaymentRail paymentRail;

    /**
     * ZimSwitch COPYandPAY widget artifacts, present while a card checkout
     * is open ({@code status=PROCESSING}, rail {@code ZIMSWITCH_CARD}). The
     * FE renders:
     * <pre>
     *   &lt;script src="{checkoutScriptUrl}" integrity="{checkoutIntegrity}"
     *           crossorigin="anonymous"&gt;&lt;/script&gt;
     *   &lt;form action="{shopperResultUrl}" class="paymentWidgets"
     *         data-brands="{checkoutBrands}"&gt;&lt;/form&gt;
     * </pre>
     * Card data goes browser → gateway; it never touches our servers. On
     * landing back on {@code shopperResultUrl} the FE IGNORES the
     * {@code resourcePath} query parameter and simply re-POSTs
     * {@code /payments} with the same order key — the backend resolves the
     * status server-side and answers SUCCESS / PROCESSING accordingly.
     * {@code checkoutExpiresAt} is the deadline after which the checkout
     * lapses and a fresh POST mints a new one.
     */
    private String checkoutId;
    private String checkoutScriptUrl;
    private String checkoutIntegrity;
    private String checkoutBrands;
    private String shopperResultUrl;
    private LocalDateTime checkoutExpiresAt;
}
