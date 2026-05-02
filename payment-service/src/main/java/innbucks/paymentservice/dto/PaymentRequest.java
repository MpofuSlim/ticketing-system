package innbucks.paymentservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class PaymentRequest {

    @NotNull(message = "bookingId is required")
    private UUID bookingId;

    // Optional in the dummy flow — accepted for shape but not used to gate
    // anything. A real payment service would validate the amount against the
    // booking's totalAmount before charging.
    @Positive(message = "amount must be positive when provided")
    private BigDecimal amount;

    private String currency;

    // e.g. "4242" — purely cosmetic, echoed back on the receipt.
    private String cardLast4;
}
