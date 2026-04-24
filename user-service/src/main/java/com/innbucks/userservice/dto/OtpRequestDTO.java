package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(name = "OtpRequest", description = "Request a new OTP. Used for both the initial send and retries.")
public class OtpRequestDTO {

    @NotBlank(message = "Phone number is required")
    @Schema(description = "Phone number the OTP will be delivered to.", example = "+263771234567")
    private String phoneNumber;
}
