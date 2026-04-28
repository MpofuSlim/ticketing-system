package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.CustomerProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CustomerTier2RegisterDTO {

    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "ID number is required")
    private String idNumber;

    private String passportNumber;

    @NotBlank(message = "Address is required")
    private String address;

    @NotNull(message = "Gender is required")
    private CustomerProfile.Gender gender;

    @NotBlank(message = "Selfie picture is required")
    @Pattern(
            regexp = "^(data:image/[a-zA-Z+.-]+;base64,)?[A-Za-z0-9+/]+={0,2}$",
            message = "Selfie picture must be a base64-encoded image (optionally prefixed with a data URL)"
    )
    private String selfiePicture;
}
