package com.innbucks.userservice.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class RegisterRequestDTO {

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @Email(message = "Invalid email")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    private String role = "AGENT";

    // Agent-specific fields
    private String businessName;
    private String businessAddress;
    private String businessEmail;
    private String businessPhoneNumber;
    private String registrationNumber;

    // Will hold the file path after upload is processed
    private String metaData;
}
