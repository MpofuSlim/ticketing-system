package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
@Schema(name = "RegisterRequest",
        description = "Payload for creating a system-user account. `roles` is a list — a single user may hold any combination of SUPER_ADMIN, EVENT_ORGANIZER, MERCHANT_ADMIN. Default microservices are assigned by the server based on the role(s) granted.")
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

    @Schema(example = "[\"EVENT_ORGANIZER\"]",
            description = "Roles assigned to this system account. Allowed values: SUPER_ADMIN, EVENT_ORGANIZER, MERCHANT_ADMIN. SUPER_ADMIN has access to all endpoints.")
    @NotNull(message = "Roles are required")
    @Size(min = 1, message = "At least one role is required")
    private List<@NotBlank(message = "Role values must be non-blank") String> roles;
}
