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
 * Response body for {@code POST /payments/innbucks}. Mirrors the dummy
 * {@code PaymentResponse} shape (so a future cutover keeps the FE contract
 * stable) but adds two veengu-side fields useful for support / receipt UX:
 * {@code paymentReference} (stable identifier carried into veengu) and
 * {@code upstreamReference} (veengu's internal transaction id, populated on
 * SUCCESS).
 *
 * <p>On a terminal rejection ({@code status=FAILED}), {@code upstreamCode} +
 * {@code upstreamMessage} carry the veengu reason code (e.g.
 * {@code NOT_SUFFICIENT_FUNDS}) so the FE can render a specific message
 * instead of "payment failed". On PROCESSING (delivered as 202 Accepted)
 * the FE should poll the booking's status — the reconciler will resolve
 * the payment shortly.
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

    @Schema(description = "UTC timestamp when the payment reached its terminal state (or was accepted for PROCESSING).",
            example = "2026-06-08T15:48:00")
    private LocalDateTime processedAt;

    public enum Status { SUCCESS, PROCESSING, FAILED }
}
