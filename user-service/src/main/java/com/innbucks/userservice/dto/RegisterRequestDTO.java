package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
@Schema(name = "RegisterRequest",
        description = "Payload for creating a system-user account. The caller picks one or more " +
                "service bundles in `defaultServices` (e.g. `ticketing`, `loyalty`). The server " +
                "derives the appropriate role(s) and the underlying microservice access from those " +
                "selections — `ticketing` grants events/seats/bookings/payments behind the scenes, " +
                "`loyalty` grants loyalty/payments. Pick both to be granted access to everything.")
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

    @Schema(example = "[\"ticketing\"]",
            description = "Service bundles to enrol this user into. Allowed values: `ticketing`, `loyalty`.")
    @NotNull(message = "defaultServices is required")
    @Size(min = 1, message = "At least one default service is required")
    private List<@NotBlank(message = "Service values must be non-blank") String> defaultServices;
}
