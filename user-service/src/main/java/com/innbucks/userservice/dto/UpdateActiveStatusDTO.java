package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(name = "UpdateActiveStatus", description = "Payload for activating or deactivating a user account.")
public class UpdateActiveStatusDTO {

    @NotNull(message = "active field is required")
    @Schema(example = "true", description = "Set to true to activate the account, false to deactivate it.")
    private Boolean active;
}
