package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How the customer pays at the shop. Only POINTS is accepted: the customer pays " +
        "with their existing points balance. CASH and CASH_AND_POINTS are kept so an older client gets " +
        "a clear 400 rather than an unreadable body; points for a cash purchase are earned at the till " +
        "or by scanning the shop's QR code.")
public enum PaymentMethod {
    CASH,
    POINTS,
    CASH_AND_POINTS
}
