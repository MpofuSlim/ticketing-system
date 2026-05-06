package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(name = "CustomerTier3RegisterRequest",
        description = "Biometrics reference + device registration to upgrade to Tier 3.")
public class CustomerTier3RegisterDTO {

    @Schema(example = "bio-ref-a1b2c3d4e5f6",
            description = "Opaque reference token returned by the biometrics SDK after a successful liveness check.")
    @NotBlank(message = "Biometrics reference is required")
    private String biometricsReference;

    @Valid
    @NotNull(message = "Device registration is required")
    private DeviceRegistrationDTO device;
}
