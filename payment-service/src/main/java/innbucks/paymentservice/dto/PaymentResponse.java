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

    public enum Status { SUCCESS, FAILED }

    private UUID transactionId;
    private UUID bookingId;
    private Status status;
    private BigDecimal amountPaid;
    private String currency;
    private String cardLast4;
    private String confirmationNumber;
    private LocalDateTime processedAt;
}
