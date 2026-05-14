package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.CustomerProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import lombok.Data;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(name = "CustomerTier2RegisterRequest",
        description = "Identity details to upgrade an existing Tier-1 account to Tier 2. " +
                "The `msisdn` field identifies the Tier-1 record to upgrade.")
public class CustomerTier2RegisterDTO {

    @Schema(example = "Sarah")
    @NotBlank(message = "First name is required")
    private String firstName;

    @Schema(example = "Tiffany", nullable = true)
    private String middleName;

    @Schema(example = "Moyo")
    @NotBlank(message = "Last name is required")
    private String lastName;

    @Schema(example = "2001-01-01", description = "Date of birth (ISO-8601, must be in the past).")
    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    @Schema(example = "FEMALE", allowableValues = {"MALE", "FEMALE", "OTHER"})
    @NotNull(message = "Gender is required")
    private CustomerProfile.Gender gender;

    @Schema(example = "0712345678", description = "Phone number used at Tier-1 registration; identifies the customer to upgrade.")
    @NotBlank(message = "msisdn is required")
    private String msisdn;

    @Schema(example = "5337888V72", description = "National ID number.")
    @NotBlank(message = "National ID is required")
    private String nationalId;

    @Schema(example = "sarah@example.com")
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String email;

    @Valid
    @NotNull(message = "Address is required")
    private Address address;

    @Schema(description = "Open key/value bag for caller-supplied extras. Persisted as JSON on the customer profile.",
            nullable = true)
    private Map<String, String> clientCustomFields = new LinkedHashMap<>();

    @Data
    @Schema(name = "CustomerAddress",
            description = "Postal/street address. All fields required.")
    public static class Address {
        @Schema(example = "P.O. Box 12345")
        @NotBlank(message = "Street is required")
        private String street1;

        @Schema(example = "Nairobi")
        @NotBlank(message = "City is required")
        private String city;

        @Schema(example = "00100")
        @NotBlank(message = "Post code is required")
        private String postCode;

        @Schema(example = "Kenya")
        @NotBlank(message = "Country is required")
        private String country;
    }
}
