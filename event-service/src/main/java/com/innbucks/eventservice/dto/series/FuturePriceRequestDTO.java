package com.innbucks.eventservice.dto.series;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Body of {@code PUT /events/series/{seriesId}/future-prices} (TIC-71). */
@Data
@Schema(name = "FuturePriceRequest", description = """
        A ticket type's full price on the template and every date that has not started. Tickets already sold \
        keep the price they were sold at.""")
public class FuturePriceRequestDTO {

    @Schema(description = "Required. The ticket type's name, as on the template (letter case ignored).",
            example = "VIP", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "categoryName is required")
    @Size(max = 100, message = "categoryName must be at most 100 characters")
    private String categoryName;

    @Schema(description = "Required. The new full price, greater than 0, at most 2 decimals. It must stay above "
            + "every price tier that can still apply.", example = "150.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "price is required")
    @DecimalMin(value = "0.00", inclusive = false, message = "Price must be greater than 0")
    @Digits(integer = 12, fraction = 2, message = "Use at most 2 decimals.")
    private BigDecimal price;
}
