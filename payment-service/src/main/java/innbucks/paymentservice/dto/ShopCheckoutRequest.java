package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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

    // 0 is allowed (and meaningful — it's how a CASH-only payment encodes the
    // unused points leg). The "must be > 0 for this paymentMethod" rule is
    // enforced cross-field by PaymentController#validateAmounts so the error
    // message can name the offending combination.
    @Schema(example = "10.00", nullable = true,
            description = "Cash amount in the merchant's currency. Required (> 0) when paymentMethod is CASH or CASH_AND_POINTS; must be omitted or 0 for POINTS.")
    @PositiveOrZero(message = "cashAmount must be >= 0")
    private BigDecimal cashAmount;

    @Schema(example = "200.0000", nullable = true,
            description = "Points to spend from the wallet. Required (> 0) when paymentMethod is POINTS or CASH_AND_POINTS; must be omitted or 0 for CASH.")
    @PositiveOrZero(message = "pointsAmount must be >= 0")
    private BigDecimal pointsAmount;

    @Schema(example = "POS-20260514-0007", nullable = true,
            description = "Optional external reference. Used on the loyalty PURCHASE row for idempotency (per-merchant unique).")
    private String reference;
}
