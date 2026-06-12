package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Internal outcome of an InnBucks 2D-code payment attempt (consumed by both
 * payment controllers; {@code PaymentController} reshapes it onto the FE's
 * historical stub contract).
 *
 * <p>The normal outcome is {@code PROCESSING} + {@code paymentCode}: an
 * InnBucks PAYMENT code was issued, delivered to the customer's phone, and
 * awaits their approval in the InnBucks app/USSD — the reconciler's poller
 * confirms the booking once InnBucks reports the code Paid. {@code SUCCESS}
 * with a confirmation number appears on replays of already-resolved
 * payments. On a terminal rejection ({@code status=FAILED}),
 * {@code upstreamCode} + {@code upstreamMessage} carry the InnBucks reason.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "InnbucksPaymentResponse",
        description = "Outcome of an InnBucks/veengu-backed payment attempt.")
public class InnbucksPaymentResponse {

    @Schema(description = "Stable reference for this payment; carried into veengu and logged on every state transition.",
            example = "TKT-PMT-f0e1d2c3-4567-890a-bcde-f01234567890")
    private String paymentReference;

    @Schema(description = "Booking the payment belongs to (echoed from the request).",
            example = "a3b9c1d2-1234-5678-9abc-def012345678")
    private UUID bookingId;

    @Schema(description = "Final or interim outcome.")
    private Status status;

    @Schema(description = "Amount actually debited; sourced from the booking's totalAmount.", example = "100.00")
    private BigDecimal amountPaid;

    @Schema(description = "ISO 4217 currency code.", example = "USD")
    private String currency;

    @Schema(description = "Booking-service's confirmation number (populated on SUCCESS).",
            example = "INN-20260608-AB12CD")
    private String confirmationNumber;

    @Schema(description = "Veengu's internal transaction id (populated on SUCCESS).",
            example = "VNG-9af-2026-06-08-001")
    private String upstreamReference;

    @Schema(description = "Veengu's error code on a terminal rejection (e.g. NOT_SUFFICIENT_FUNDS).",
            example = "NOT_SUFFICIENT_FUNDS")
    private String upstreamCode;

    @Schema(description = "Veengu's human-readable error message on a terminal rejection.",
            example = "Customer balance insufficient for transaction")
    private String upstreamMessage;

    @Schema(description = "The InnBucks payment code the customer approves in their own app/USSD. "
            + "Surfaced to the FE for the checkout screen — the FE renders the code (and QR below) "
            + "directly; there is no out-of-band delivery. Present while status=PROCESSING.",
            example = "701285660")
    private String paymentCode;

    @Schema(description = "InnBucks-rendered QR image (base64) encoding the same payment — render it "
            + "so the customer can Scan-to-Pay in the InnBucks app instead of typing the code. "
            + "May be null; the numeric code always works.",
            example = "iVBORw0KGgoAAAANSUhEUg...")
    private String paymentQrCode;

    @Schema(description = "UTC deadline for approving the payment code; after this the payment "
            + "expires and the booking can be paid again with a fresh code.",
            example = "2026-06-11T15:58:00")
    private LocalDateTime paymentCodeExpiresAt;

    @Schema(description = "UTC timestamp when the payment reached its terminal state (or was accepted for PROCESSING).",
            example = "2026-06-08T15:48:00")
    private LocalDateTime processedAt;

    public enum Status { SUCCESS, PROCESSING, FAILED }
}
