package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How the customer pays at the shop counter. " +
        "CASH = money only (no points are spent, points are earned per loyalty rules). " +
        "POINTS = the customer pays entirely with their existing points balance. " +
        "CASH_AND_POINTS = split: some money + some points; the cash portion earns points per rules, " +
        "the points portion is burned from the wallet.")
public enum PaymentMethod {
    CASH,
    POINTS,
    CASH_AND_POINTS
}
