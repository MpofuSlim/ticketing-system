package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request body for {@code POST /payments/innbucks}. Mirrors the dummy
 * {@code PaymentRequest} shape but deliberately omits the {@code cardLast4}
 * field — InnBucks-backed payments draw on the customer's wallet, there is no
 * card. The customer's MSISDN comes from the JWT, NOT the body (defence
 * against MSISDN-spoofing for charging the wrong customer's wallet).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "InnbucksPaymentRequest",
        description = "Initiate a real InnBucks/veengu-backed payment for a booking.")
public class InnbucksPaymentRequest {

    @NotNull(message = "bookingId is required")
    @Schema(description = "Booking UUID to pay for. The amount and currency are derived from the booking server-side.",
            example = "a3b9c1d2-1234-5678-9abc-def012345678", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID bookingId;
}
