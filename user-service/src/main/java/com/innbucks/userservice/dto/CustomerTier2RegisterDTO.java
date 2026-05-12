package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.CustomerProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(name = "CustomerTier2RegisterRequest",
        description = "Identity details to upgrade an existing Tier-1 account to Tier 2.")
public class CustomerTier2RegisterDTO {

    @Schema(example = "Sedrick", description = "First (given) name as it appears on the national ID.")
    @NotBlank(message = "First name is required")
    private String firstName;

    @Schema(example = "Takunda", nullable = true, description = "Middle name(s) — optional.")
    private String middleName;

    @Schema(example = "Elvis", description = "Last (family) name as it appears on the national ID.")
    @NotBlank(message = "Last name is required")
    private String lastName;

    @Schema(example = "63-123456A78", description = "National ID number.")
    @NotBlank(message = "ID number is required")
    private String idNumber;

    @Schema(example = "ZW1234567", nullable = true, description = "Passport number — required if no national ID.")
    private String passportNumber;

    @Schema(example = "12 Samora Machel Ave, Harare")
    @NotBlank(message = "Address is required")
    private String address;

    @Schema(example = "FEMALE", allowableValues = {"MALE", "FEMALE", "OTHER"})
    @NotNull(message = "Gender is required")
    private CustomerProfile.Gender gender;

    @Schema(example = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAA...",
            description = "Base64-encoded selfie image (JPEG or PNG). " +
                    "May include a data-URL prefix (e.g. `data:image/jpeg;base64,`).")
    @NotBlank(message = "Selfie picture is required")
    @Pattern(
            regexp = "^(data:image/[a-zA-Z+.-]+;base64,)?[A-Za-z0-9+/]+={0,2}$",
            message = "Selfie picture must be a base64-encoded image (optionally prefixed with a data URL)"
    )
    private String selfiePicture;
}
