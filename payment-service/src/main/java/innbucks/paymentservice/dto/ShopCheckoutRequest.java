package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(name = "ShopCheckoutRequest",
        description = "Customer pays at a shop with points: `pointsAmount` is burned from the " +
                "customer's wallet. Only `paymentMethod=POINTS` is accepted. CASH and " +
                "CASH_AND_POINTS are refused with 400, because this endpoint collects no cash " +
                "and so cannot award points for it.")
public class ShopCheckoutRequest {

    @Schema(example = "5b1c2d3e-4567-890a-bcde-f01234567890", description = "Shop where the checkout happens.")
    @NotNull(message = "shopId is required")
    private UUID shopId;

    /**
     * @deprecated The server now derives the customer's MSISDN from the
     * authenticated JWT principal — values supplied in the body are silently
     * ignored. Kept on the DTO so old FE clients sending the field don't 400
     * with "unknown field"; a future release will remove it entirely.
     */
    @Deprecated
    @Schema(example = "0712345678",
            description = "Deprecated — ignored by the server, the customer's MSISDN comes from the JWT.",
            deprecated = true,
            nullable = true)
    private String msisdn;

    @Schema(example = "POINTS", description = "Must be POINTS. CASH and CASH_AND_POINTS are refused with 400.")
    @NotNull(message = "paymentMethod is required")
    private PaymentMethod paymentMethod;

    // 0 is allowed (a POINTS payment may send the unused cash field as 0).
    // The cross-field rules are enforced by PaymentController#validateAmounts
    // so the error message can name the offending combination.
    @Schema(example = "0", nullable = true,
            description = "Must be omitted or 0. A positive cash amount is refused: this endpoint collects no cash.")
    @PositiveOrZero(message = "cashAmount must be >= 0")
    private BigDecimal cashAmount;

    @Schema(example = "200.0000", nullable = true,
            description = "Points to spend from the wallet. Required (> 0).")
    @PositiveOrZero(message = "pointsAmount must be >= 0")
    private BigDecimal pointsAmount;
}
