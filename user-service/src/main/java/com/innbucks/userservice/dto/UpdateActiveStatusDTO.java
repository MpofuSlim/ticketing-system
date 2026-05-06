package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(name = "UpdateActiveStatusRequest",
        description = "Payload for activating or deactivating a user. Only SUPER_ADMIN can call this.")
public class UpdateActiveStatusDTO {

    @Schema(example = "true",
            description = "Set true to approve/activate the user, false to deactivate.")
    @NotNull(message = "active is required")
    private Boolean active;
}
