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
     * SUCCESS): returned when the bank accepted the debit but the outcome
     * isn't authoritative yet — the reconciler resolves it and the booking
     * confirms within ~a minute. FE treatment: same as SUCCESS visually
     * ("payment received"); the confirmation number lands on the booking.
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
}
