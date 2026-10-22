package com.innbucks.bookingservice.dto.promo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.UUID;

/** Body of {@code POST /bookings/promo-codes}: the event, the code, then its terms. */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "CreatePromoCodeRequest")
public class CreatePromoCodeRequestDTO extends PromoCodeTermsDTO {

    @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "eventId is required")
    private UUID eventId;

    @Schema(example = "EARLY10", requiredMode = Schema.RequiredMode.REQUIRED,
            description = "3-40 letters, digits, '-' or '_'; stored in upper case and matched in any case. "
                    + "Unique per event. It cannot be changed afterwards: create a new code instead.")
    @NotBlank(message = "code is required")
    @Size(max = 40, message = "code must be at most 40 characters")
    private String code;
}
