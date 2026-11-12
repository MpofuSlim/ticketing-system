package innbucks.paymentservice.dto;

import innbucks.paymentservice.order.OrderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(name = "PaymentRequest",
        description = "Initiates payment for a pending order. Identify the order EXACTLY one way: "
                + "`bookingId` (ticket bookings — the historical contract, implies orderType BOOKING) "
                + "OR `orderType` + `orderRef` (MARKETPLACE + the MKT-... order reference, or "
                + "LOYALTY_VOUCHER + the VCH-... voucher purchase-order reference). "
                + "Amount and currency are always read server-side from the order record — the client "
                + "cannot override either.")
public class PaymentRequest {

    @Schema(example = "a3b9c1d2-1234-5678-9abc-def012345678",
            description = "UUID of the PENDING booking to pay for. The historical contract — mutually "
                    + "exclusive with orderType/orderRef; implies orderType BOOKING.")
    private UUID bookingId;

    @Schema(example = "MARKETPLACE",
            description = "Which product the order belongs to. Required (together with orderRef) when "
                    + "bookingId is absent; must NOT be combined with bookingId.")
    private OrderType orderType;

    @Schema(example = "MKT-4F9A1C22B7D3",
            description = "The product-side order reference — the marketplace order ref for MARKETPLACE, "
                    + "the VCH-... purchase-order ref for LOYALTY_VOUCHER (from "
                    + "POST /loyalty/vouchers/purchase), or a booking UUID for BOOKING. Required "
                    + "together with orderType.")
    private String orderRef;

    @Schema(example = "ZIMSWITCH_CARD",
            description = "Additive: which rail to collect on. Omitted/null = INNBUCKS_CODE (the "
                    + "historical contract — an InnBucks code/QR the customer approves in their app). "
                    + "ZIMSWITCH_CARD returns COPYandPAY widget artifacts (checkoutId + script URL + "
                    + "integrity) for card entry instead. ECOCASH pushes a wallet PIN prompt to the "
                    + "order's phone number — nothing to render; show 'approve on your phone' and poll "
                    + "(promptExpiresAt drives the countdown). One active payment per order across ALL "
                    + "rails: while an attempt is open on one rail, POSTing with another returns "
                    + "the open attempt's receipt unchanged (switch rails after it lapses).")
    private innbucks.paymentservice.entity.PaymentRail paymentRail;

    @Size(max = 256)
    @Schema(example = "/v1/checkouts/8a82944a4cc25ebf014cc2c782423202/payment",
            description = "Card rail only, and only from the card result page: the `resourcePath` query "
                    + "parameter ZimSwitch put on the redirect to `shopperResultUrl`, passed through as it "
                    + "arrived. It tells us the shopper has finished with the card form, which is the one "
                    + "moment we check the payment with ZimSwitch before the checkout's 30 minutes are up: "
                    + "checking earlier kills the card form under a shopper who is still typing. Never send it "
                    + "from anywhere else. It is compared with our own checkout id and never used to build a "
                    + "request; a value naming another checkout is ignored.")
    private String resourcePath;
}
