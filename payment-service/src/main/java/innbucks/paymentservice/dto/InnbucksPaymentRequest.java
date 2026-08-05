package innbucks.paymentservice.dto;

import innbucks.paymentservice.order.OrderType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request body for {@code POST /payments/innbucks}. Identifies the order to
 * pay EXACTLY one way: {@code bookingId} (the historical shape, implies
 * orderType BOOKING) or {@code orderType} + {@code orderRef} (additive — e.g.
 * MARKETPLACE + the MKT-... order reference). The customer's MSISDN comes
 * from the JWT, NOT the body (defence against MSISDN-spoofing for charging
 * the wrong customer's wallet).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "InnbucksPaymentRequest",
        description = "Initiate a real InnBucks 2D-code payment for an order (booking or marketplace).")
public class InnbucksPaymentRequest {

    @Schema(description = "Booking UUID to pay for — the historical contract; mutually exclusive with "
            + "orderType/orderRef and implies orderType BOOKING. Amount and currency are derived from "
            + "the order server-side.",
            example = "a3b9c1d2-1234-5678-9abc-def012345678")
    private UUID bookingId;

    @Schema(description = "Which product the order belongs to. Required (together with orderRef) when "
            + "bookingId is absent; must NOT be combined with bookingId.",
            example = "MARKETPLACE")
    private OrderType orderType;

    @Schema(description = "The product-side order reference — the marketplace order ref for MARKETPLACE, "
            + "or a booking UUID for BOOKING. Required together with orderType.",
            example = "MKT-4F9A1C22B7D3")
    private String orderRef;
}
