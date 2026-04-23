package com.innbucks.userservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CustomerTier3RegisterDTO {

    @NotBlank(message = "Biometrics reference is required")
    private String biometricsReference;

    @Valid
    @NotNull(message = "Device registration is required")
    private DeviceRegistrationDTO device;
}
