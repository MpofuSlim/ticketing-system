package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(name = "PaymentRequest",
        description = "Initiates payment for a PENDING booking. " +
                "The amount is read from the booking record — the client cannot override it.")
public class PaymentRequest {

    @Schema(example = "a3b9c1d2-1234-5678-9abc-def012345678",
            description = "UUID of the PENDING booking to pay for.")
    @NotNull(message = "bookingId is required")
    private UUID bookingId;

    @Schema(example = "USD", nullable = true,
            description = "Payment currency. Defaults to USD when omitted. Must match the booking's currency.")
    private String currency;

    @Schema(example = "4242", nullable = true,
            description = "Last 4 digits of the card used — cosmetic only. Echoed back on the receipt, not processed.")
    private String cardLast4;
}
