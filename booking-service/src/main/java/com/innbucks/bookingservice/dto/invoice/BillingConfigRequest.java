package com.innbucks.bookingservice.dto.invoice;

import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Admin upsert of an organizer's billing terms. */
@Schema(name = "BillingConfigRequest", description = "Set an organizer's commission rate and billing cycle.")
public record BillingConfigRequest(
        @Schema(description = "Commission as a percentage (0..100).", example = "12.5")
        @NotNull(message = "commissionRate is required")
        @DecimalMin(value = "0.0", message = "commissionRate must be >= 0")
        @DecimalMax(value = "100.0", message = "commissionRate must be <= 100")
        @Digits(integer = 3, fraction = 4, message = "commissionRate has at most 4 decimal places")
        BigDecimal commissionRate,

        @Schema(description = "WEEKLY or MONTHLY. Defaults to the deployment default when omitted.",
                example = "MONTHLY", nullable = true)
        BillingCycle billingCycle,

        @Schema(description = "ISO currency for this organizer's invoices. Defaults to the cell currency.",
                example = "USD", nullable = true)
        String currency
) {
}
