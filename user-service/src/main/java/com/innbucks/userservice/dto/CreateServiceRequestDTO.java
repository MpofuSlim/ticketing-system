package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "CreateServiceRequest",
        description = "Payload for an authenticated user to request access to an additional default service.")
public class CreateServiceRequestDTO {

    @NotBlank(message = "service is required")
    @Schema(example = "loyalty",
            description = "Bundle name to request access to. Must be one of the known platform bundles (e.g. 'ticketing', 'loyalty').")
    private String service;

    @NotBlank(message = "reason is required")
    @Size(max = 1000, message = "reason must be 1000 characters or fewer")
    @Schema(example = "We are launching a customer rewards programme alongside our event ticketing.",
            description = "Free-text justification shown to the SUPER_ADMIN reviewing the request.")
    private String reason;
}
