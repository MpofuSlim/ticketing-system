package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.CustomerProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @NotBlank(message = "Selfie picture path is required")
    private String selfiePicturePath;
}
