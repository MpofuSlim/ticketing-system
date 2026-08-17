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
     * <p>Clients should switch on {@code stage} and treat an unrecognised
     * value as {@link #IN_PROGRESS} (new values may be added; none will be
     * removed).
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
         * The instrument lapsed unpaid. Nothing charged, and the order's
         * payment slot is free — offer "Pay again", which mints a fresh one.
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
         * the mapping lives, so {@code stage} and {@code fundsCaptured} can
         * never disagree.
         *
         * <p>The default is deliberately {@code null} (unknown) rather than
         * {@code false}: a stage added later without updating this switch
         * should fail SAFE by admitting ignorance, not by asserting that no
         * money moved.
         */
        public Boolean fundsCaptured() {
            return switch (this) {
                case PAYMENT_RECEIVED, COMPLETED -> Boolean.TRUE;
                case AWAITING_PAYMENT, IN_PROGRESS, INSTRUMENT_EXPIRED, PAYMENT_UNAVAILABLE -> Boolean.FALSE;
                case VERIFYING -> null;
            };
        }
    }

    /** Set {@link #stage} and derive {@link #fundsCaptured} together. */
    public static class PaymentResponseBuilder {
        public PaymentResponseBuilder stage(Stage stage) {
            this.stage = stage;
            this.fundsCaptured = stage == null ? null : stage.fundsCaptured();
            return this;
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
    private Stage stage;

    /**
     * <b>Has money actually left the customer?</b> Deliberately a nullable
     * {@code Boolean}, not a primitive, because there are THREE honest
     * answers and only two of them are booleans:
     *
     * <ul>
     *   <li>{@code true}  — captured ({@link Stage#PAYMENT_RECEIVED},
     *       {@link Stage#COMPLETED}).</li>
     *   <li>{@code false} — definitively not captured (awaiting payment,
     *       in progress, expired, unavailable).</li>
     *   <li>{@code null}  — <b>UNKNOWN</b> ({@link Stage#VERIFYING}). The
     *       row is parked for an operator. Returning {@code false} here would
     *       tell a customer who may have paid that nothing was charged;
     *       returning {@code true} would promise a ticket we cannot yet back.
     *       Treat null as "don't claim either way".</li>
     * </ul>
     *
     * Derived from {@link #stage} in one place so the two cannot drift.
     *
     * <p><b>{@code ALWAYS} is load-bearing, not decoration.</b> Several sibling
     * DTOs in this package carry {@code @JsonInclude(NON_NULL)} at class level,
     * so adding it here later would be the natural-looking change — and it
     * would silently delete the key for exactly the VERIFYING case, leaving a
     * client unable to distinguish "we don't know" from "old server that
     * doesn't send this field". Field-level inclusion beats class-level, so
     * this survives that edit. {@code PaymentResponseSerializationTest} fails
     * if the key ever goes missing.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private Boolean fundsCaptured;

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
