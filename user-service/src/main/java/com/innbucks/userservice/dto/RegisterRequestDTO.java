package com.innbucks.userservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.innbucks.userservice.util.TrimToNullDeserializer;
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

    // A person name: one or more letters of ANY script (so José / 李雷 are fine),
    // plus spaces, hyphens and apostrophes (O'Brien, Anne-Marie). Digits and
    // other symbols are rejected. Must start with a letter. Leading/trailing
    // whitespace is stripped before this runs (see TrimToNullDeserializer), so a
    // trailing space no longer trips the "must start with a letter" rule.
    private static final String NAME_REGEX = "^\\p{L}[\\p{L} '\\-]*$";

    // Stricter than @Email (which accepts a bare "john@example" with no TLD):
    // exactly one @, non-empty local part, and a dot-separated domain, no
    // whitespace anywhere.
    private static final String EMAIL_REGEX = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    // BPO / tax number: 6–20 characters of digits and hyphens, at least one
    // digit (so "12345678-0001" is fine but "ABCDEFGH" and "1234@5678" are not).
    private static final String BPO_REGEX = "^(?=.*\\d)[0-9\\-]{6,20}$";

    @Schema(example = "Alice")
    @JsonDeserialize(using = TrimToNullDeserializer.class)
    @NotBlank(message = "First name is required")
    @Size(max = 50, message = "First name must not exceed 50 characters")
    @Pattern(regexp = NAME_REGEX,
            message = "First name may contain only letters, spaces, hyphens and apostrophes")
    private String firstName;

    @Schema(example = "Jane", nullable = true)
    @JsonDeserialize(using = TrimToNullDeserializer.class)
    @Size(max = 50, message = "Middle name must not exceed 50 characters")
    @Pattern(regexp = NAME_REGEX,
            message = "Middle name may contain only letters, spaces, hyphens and apostrophes")
    private String middleName;

    @Schema(example = "Moyo")
    @JsonDeserialize(using = TrimToNullDeserializer.class)
    @NotBlank(message = "Last name is required")
    @Size(max = 50, message = "Last name must not exceed 50 characters")
    @Pattern(regexp = NAME_REGEX,
            message = "Last name may contain only letters, spaces, hyphens and apostrophes")
    private String lastName;

    @Schema(example = "+263771234567")
    @JsonDeserialize(using = TrimToNullDeserializer.class)
    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @Schema(example = "alice@innbucks.co.zw")
    @JsonDeserialize(using = TrimToNullDeserializer.class)
    @Email(message = "Invalid email")
    @NotBlank(message = "Email is required")
    @Size(max = 254, message = "Email must not exceed 254 characters")
    @Pattern(regexp = EMAIL_REGEX,
            message = "Email must be a valid address, e.g. name@example.com")
    private String email;

    @Schema(example = "Zimbabwe")
    @JsonDeserialize(using = TrimToNullDeserializer.class)
    @NotBlank(message = "Country is required")
    @Size(max = 100, message = "Country must not exceed 100 characters")
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
    @JsonDeserialize(using = TrimToNullDeserializer.class)
    @Size(max = 200, message = "Business name must not exceed 200 characters")
    private String businessName;

    @Schema(example = "123 Samora Machel Ave, Harare", nullable = true,
            description = "Physical address of the business. Required when isBusiness is true.")
    @JsonDeserialize(using = TrimToNullDeserializer.class)
    @Size(max = 255, message = "Business address must not exceed 255 characters")
    private String businessAddress;

    @Schema(example = "accounts@innbucks.co.zw", nullable = true,
            description = "Business contact email. OPTIONAL even for a business account — surfaces as " +
                    "the organizer's `email` on event listings when set. Validated for format only when " +
                    "provided; null/blank is accepted.")
    @JsonDeserialize(using = TrimToNullDeserializer.class)
    @Email(message = "Invalid business email")
    @Size(max = 254, message = "Business email must not exceed 254 characters")
    private String businessEmail;

    @Schema(example = "12345678-0001", nullable = true,
            description = "Business BPO / tax number (surfaced to merchants as TIN/VAT). Required when " +
                    "isBusiness is true. 6–20 characters, digits and hyphens only, and unique across " +
                    "registered businesses.")
    @JsonDeserialize(using = TrimToNullDeserializer.class)
    @Size(max = 20, message = "BPO number must not exceed 20 characters")
    @Pattern(regexp = BPO_REGEX,
            message = "BPO number must be 6–20 characters of digits and hyphens (at least one digit)")
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
