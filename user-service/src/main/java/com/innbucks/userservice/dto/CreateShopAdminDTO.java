package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(name = "CreateShopAdmin",
        description = "Payload for a MERCHANT_ADMIN to onboard a SHOP_ADMIN at one of their shops. " +
                      "The shopId must reference a shop under the caller's merchant.")
public class CreateShopAdminDTO {

    @NotBlank(message = "firstName is required")
    @Schema(example = "Tendai")
    private String firstName;

    @Schema(example = "M", nullable = true)
    private String middleName;

    @NotBlank(message = "lastName is required")
    @Schema(example = "Moyo")
    private String lastName;

    @Email(message = "email must be valid")
    @NotBlank(message = "email is required")
    @Schema(example = "tendai@pizza-avondale.co.zw")
    private String email;

    @NotBlank(message = "phoneNumber is required")
    @Schema(example = "+263771234567")
    private String phoneNumber;

    @NotBlank(message = "password is required")
    @Size(min = 8, message = "password must be at least 8 characters")
    @Schema(example = "ChangeMe123!")
    private String password;

    @NotNull(message = "shopId is required")
    @Schema(example = "11111111-aaaa-bbbb-cccc-222222222222",
            description = "Shop the new SHOP_ADMIN will administer.")
    private UUID shopId;
}
