package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(name = "RegisterRequest",
        description = "Payload for creating a system-user account (TENANT, ADMIN, MERCHANT_ADMIN, etc.).")
public class RegisterRequestDTO {

    @Schema(example = "Alice")
    @NotBlank(message = "First name is required")
    private String firstName;

    @Schema(example = "Jane", nullable = true)
    private String middleName;

    @Schema(example = "Moyo")
    @NotBlank(message = "Last name is required")
    private String lastName;

    @Schema(example = "+263771234567")
    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @Schema(example = "alice@innbucks.co.zw")
    @Email(message = "Invalid email")
    @NotBlank(message = "Email is required")
    private String email;

    @Schema(example = "S3cur3Pass!")
    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @Schema(example = "TENANT",
            allowableValues = {"SYSTEM_MANAGER", "TENANT", "MERCHANT_ADMIN", "SHOP_ADMIN", "SHOP_USER", "ADMIN"},
            description = "Role assigned to this system account.")
    @NotBlank(message = "Role is required")
    private String role;
}
