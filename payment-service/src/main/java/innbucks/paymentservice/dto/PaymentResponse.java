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
