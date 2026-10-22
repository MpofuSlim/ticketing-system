package com.innbucks.bookingservice.dto.promo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/** Body of {@code POST /bookings/promo-codes/check}. */
@Data
@Schema(name = "PromoCodeCheckRequest")
public class PromoCodeCheckRequestDTO {
    @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "eventId is required")
    private UUID eventId;

    @Schema(example = "early10", requiredMode = Schema.RequiredMode.REQUIRED, description = "Any letter case.")
    @NotBlank(message = "code is required")
    @Size(max = 40, message = "code must be at most 40 characters")
    private String code;
}
