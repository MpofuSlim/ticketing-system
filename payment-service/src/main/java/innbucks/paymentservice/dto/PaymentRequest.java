package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(name = "PaymentRequest",
        description = "Initiates payment for a PENDING booking. Body carries only `bookingId`; "
                + "amount and currency are read server-side from the booking record — the client "
                + "cannot override either.")
public class PaymentRequest {

    @Schema(example = "a3b9c1d2-1234-5678-9abc-def012345678",
            description = "UUID of the PENDING booking to pay for.")
    @NotNull(message = "bookingId is required")
    private UUID bookingId;
}
