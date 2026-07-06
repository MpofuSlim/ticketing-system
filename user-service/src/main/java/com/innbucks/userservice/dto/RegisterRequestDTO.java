package com.innbucks.userservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
@Schema(name = "RegisterRequest",
        description = "Payload for registering a system-user account. No password is supplied here — the " +
                "account is created pending SUPER_ADMIN approval, and the default password is assigned only " +
                "once it is approved. The caller picks one or more service bundles in `defaultServices` " +
                "(e.g. `ticketing`, `loyalty`); the server derives the role(s) and the underlying microservice " +
                "access. `ticketing` -> EVENT_ORGANIZER; `loyalty` -> MERCHANT_ADMIN; pick both to get both. " +
                "When `isBusiness` is true, `businessName`, `businessAddress` and `bpoNumber` are required.")
public class RegisterRequestDTO {

    @Schema(example = "Alice")
    @NotBlank(message = "First name is required")
    @Size(max = 100, message = "First name must not exceed 100 characters")
    private String firstName;

    @Schema(example = "Jane", nullable = true)
    @Size(max = 100, message = "Middle name must not exceed 100 characters")
    private String middleName;

    @Schema(example = "Moyo")
    @NotBlank(message = "Last name is required")
    @Size(max = 100, message = "Last name must not exceed 100 characters")
    private String lastName;

    @Schema(example = "+263771234567")
    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @Schema(example = "alice@innbucks.co.zw")
    @Email(message = "Invalid email")
    @NotBlank(message = "Email is required")
    private String email;

    @Schema(example = "Zimbabwe")
    @NotBlank(message = "Country is required")
    private String country;

    @Schema(example = "[\"ticketing\"]",
            description = "Service bundles to enrol this user into. Allowed values: `ticketing`, `loyalty`.")
    @NotNull(message = "defaultServices is required")
    @Size(min = 1, message = "At least one default service is required")
    private List<@NotBlank(message = "Service values must be non-blank") String> defaultServices;

    @Schema(example = "true",
            description = "Whether this is a business account. When true, businessName, businessAddress and bpoNumber are required.")
    @JsonProperty("isBusiness")
    private boolean business;

    @Schema(example = "InnBucks Ticketing Ltd", nullable = true,
            description = "Legal or trading name of the business. Required when isBusiness is true.")
    @Size(max = 200, message = "Business name must not exceed 200 characters")
    private String businessName;

    @Schema(example = "123 Samora Machel Ave, Harare", nullable = true,
            description = "Physical address of the business. Required when isBusiness is true.")
    private String businessAddress;

    @Schema(example = "accounts@innbucks.co.zw", nullable = true,
            description = "Business contact email. OPTIONAL even for a business account — surfaces as " +
                    "the organizer's `email` on event listings when set. Validated for format only when " +
                    "provided; null/blank is accepted.")
    @Email(message = "Invalid business email")
    private String businessEmail;

    @Schema(example = "12345", nullable = true,
            description = "Business BPO number. Required when isBusiness is true.")
    private String bpoNumber;

    @AssertTrue(message = "businessName is required for a business account")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isBusinessNameValid() {
        return !business || (businessName != null && !businessName.isBlank());
    }

    @AssertTrue(message = "businessAddress is required for a business account")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isBusinessAddressValid() {
        return !business || (businessAddress != null && !businessAddress.isBlank());
    }

    @AssertTrue(message = "bpoNumber is required for a business account")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isBpoNumberValid() {
        return !business || (bpoNumber != null && !bpoNumber.isBlank());
    }
}
