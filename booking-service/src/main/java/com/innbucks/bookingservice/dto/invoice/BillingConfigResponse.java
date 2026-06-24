package com.innbucks.bookingservice.dto.invoice;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An organizer's effective billing terms. {@code overridden=false} means no
 * per-organizer row exists and these are the deployment defaults.
 */
@Schema(name = "BillingConfig", description = "An organizer's effective platform billing terms.")
public record BillingConfigResponse(
        @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") UUID organizerUuid,
        @Schema(description = "Commission percentage.", example = "12.5000") BigDecimal commissionRate,
        @Schema(description = "WEEKLY or MONTHLY.", example = "MONTHLY") String billingCycle,
        @Schema(example = "USD") String currency,
        @Schema(description = "True when a per-organizer override exists; false when these are deployment defaults.",
                example = "true") boolean overridden
) {
}
