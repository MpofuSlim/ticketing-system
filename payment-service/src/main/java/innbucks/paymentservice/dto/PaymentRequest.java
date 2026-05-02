package innbucks.paymentservice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class PaymentRequest {

    @NotNull(message = "bookingId is required")
    private UUID bookingId;

    // Optional. Defaults to USD on the response when omitted. The amount is
    // NOT taken from the client; it's read from booking-service's totalAmount
    // so a malicious caller can't underpay.
    private String currency;

    // Optional, cosmetic-only — echoed back on the receipt.
    private String cardLast4;
}
