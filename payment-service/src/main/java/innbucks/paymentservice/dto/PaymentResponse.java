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
    private UUID bookingId;
    private Status status;
    private BigDecimal amountPaid;
    private String currency;
    private String cardLast4;
    private String confirmationNumber;
    private LocalDateTime processedAt;

    /**
     * Additive (old FE ignores them): the InnBucks code the customer
     * approves — also delivered via WhatsApp/SMS so the CURRENT FE needs no
     * change — and its approval deadline. A future FE can render these
     * directly (e.g. show the code or deep-link
     * {@code com.innbucks.customer://purchase?paymentToken=<code>}).
     */
    private String paymentCode;
    private LocalDateTime paymentCodeExpiresAt;
}
