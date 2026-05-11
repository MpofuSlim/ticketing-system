package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(name = "BindLoyaltyMerchant",
        description = "Payload for binding a MERCHANT_ADMIN user-service profile to a loyalty-service " +
                      "merchant. After binding, the user must re-login to receive a JWT that carries " +
                      "the merchantId claim.")
public class BindLoyaltyMerchantDTO {

    @NotNull(message = "merchantId is required")
    @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789",
            description = "ID of the loyalty-service merchant the user administers.")
    private UUID merchantId;
}
