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
}
