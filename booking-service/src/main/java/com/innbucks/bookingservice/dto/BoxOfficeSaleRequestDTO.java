package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * A box office sale (V35, V36): POST /bookings's body, for the BUYER (never
 * the seller's session), plus how the buyer pays at the counter.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(name = "BoxOfficeSaleRequest",
        description = "POST /bookings's body, describing the BUYER, plus paymentMethod.")
public class BoxOfficeSaleRequestDTO extends CreateBookingRequestDTO {

    /** How the buyer pays at the counter. */
    public enum PaymentMethod { CASH, ONLINE }

    @Schema(example = "CASH", allowableValues = {"CASH", "ONLINE"}, nullable = true,
            description = "CASH (the default): the seller takes cash and the sale is CONFIRMED at once. "
                    + "ONLINE: the booking stays PENDING (held like any checkout) and the buyer pays through "
                    + "POST /payments with the returned booking id (InnBucks code, EcoCash or card); poll "
                    + "GET /payments/{transactionId} for the outcome. The answer never carries a viewToken.")
    private PaymentMethod paymentMethod;

    /** CASH unless ONLINE was asked for. */
    public PaymentMethod paymentMethodOrDefault() {
        return paymentMethod == null ? PaymentMethod.CASH : paymentMethod;
    }
}
