package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "CreateShopUser",
        description = "Payload for a SHOP_ADMIN to onboard a SHOP_USER (e.g. cashier) at their shop. " +
                      "The new user is scoped to the caller's shop automatically — there is no shopId " +
                      "field because a SHOP_ADMIN can only create staff for their own shop.")
public class CreateShopUserDTO {

    @NotBlank(message = "firstName is required")
    @Schema(example = "Rufaro")
    private String firstName;

    @Schema(example = "T", nullable = true)
    private String middleName;

    @NotBlank(message = "lastName is required")
    @Schema(example = "Ncube")
    private String lastName;

    @Email(message = "email must be valid")
    @NotBlank(message = "email is required")
    @Schema(example = "rufaro@pizza-avondale.co.zw")
    private String email;

    @NotBlank(message = "phoneNumber is required")
    @Schema(example = "+263772345678")
    private String phoneNumber;

    @NotBlank(message = "password is required")
    @Size(min = 8, message = "password must be at least 8 characters")
    @Schema(example = "ChangeMe123!")
    private String password;
}
