package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(name = "ShopCheckoutRequest",
        description = "Customer pays at a shop. The cash portion earns points per the merchant's " +
                "loyalty rules; the points portion is burned from the customer's wallet. " +
                "Set the amounts according to `paymentMethod`: " +
                "CASH → only `cashAmount`; POINTS → only `pointsAmount`; " +
                "CASH_AND_POINTS → both, each in its own native unit.")
public class ShopCheckoutRequest {

    @Schema(example = "5b1c2d3e-4567-890a-bcde-f01234567890", description = "Shop where the checkout happens.")
    @NotNull(message = "shopId is required")
    private UUID shopId;

    @Schema(example = "0712345678", description = "Customer's phone number / MSISDN. Used to look up or auto-enrol the loyalty wallet.")
    @NotBlank(message = "msisdn is required")
    private String msisdn;

    @Schema(example = "CASH_AND_POINTS")
    @NotNull(message = "paymentMethod is required")
    private PaymentMethod paymentMethod;

    @Schema(example = "10.00", nullable = true,
            description = "Cash amount in the merchant's currency. Required when paymentMethod is CASH or CASH_AND_POINTS; must be null/zero for POINTS.")
    @Positive(message = "cashAmount must be > 0 when provided")
    private BigDecimal cashAmount;

    @Schema(example = "200.0000", nullable = true,
            description = "Points to spend from the wallet. Required when paymentMethod is POINTS or CASH_AND_POINTS; must be null/zero for CASH.")
    @Positive(message = "pointsAmount must be > 0 when provided")
    private BigDecimal pointsAmount;

    @Schema(example = "POS-20260514-0007", nullable = true,
            description = "Optional external reference. Used on the loyalty PURCHASE row for idempotency (per-merchant unique).")
    private String reference;
}
