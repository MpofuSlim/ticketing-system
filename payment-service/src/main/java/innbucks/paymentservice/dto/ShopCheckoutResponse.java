package innbucks.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ShopCheckoutResponse", description = "Receipt for a shop checkout that ran through loyalty.")
public class ShopCheckoutResponse {

    @Schema(example = "f0e1d2c3-4567-890a-bcde-f01234567890")
    private UUID transactionId;

    @Schema(example = "5b1c2d3e-4567-890a-bcde-f01234567890")
    private UUID shopId;

    @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789",
            description = "Merchant that owns the shop — resolved by loyalty-service, echoed for the receipt.")
    private UUID merchantId;

    @Schema(example = "0712345678")
    private String msisdn;

    @Schema(example = "CASH_AND_POINTS")
    private PaymentMethod paymentMethod;

    @Schema(example = "10.00", description = "Cash actually paid.")
    private BigDecimal cashAmount;

    @Schema(example = "200.0000", description = "Points debited from the customer's wallet for the points portion.")
    private BigDecimal pointsRedeemed;

    @Schema(example = "12.5000", description = "Points credited to the customer for the cash portion (rules + active campaign).")
    private BigDecimal pointsEarned;

    @Schema(example = "1812.5000", description = "Customer's wallet balance after both legs settled.")
    private BigDecimal walletBalanceAfter;

    @Schema(example = "2026-05-14T10:30:00")
    private LocalDateTime processedAt;
}
