package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "CustomerTier1RegisterRequest",
        description = "Phone number + password to create a Tier-1 (walk-up) customer account.")
public class CustomerTier1RegisterDTO {

    @Schema(example = "+263771234567", description = "Customer's mobile number in E.164 format.")
    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @Schema(example = "S3cur3Pass!", description = "8 to 72 characters.")
    @NotBlank(message = "Password is required")
    // max 72: BCrypt only hashes the first 72 bytes; capping the set path also
    // stops a multi-megabyte password from burning CPU in the hash (cheap DoS).
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    private String password;
}
