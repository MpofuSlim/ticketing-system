package com.innbucks.bookingservice.dto.invoice;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Admin on-demand invoice generation for an explicit period. {@code organizerUuid}
 * null = generate for every organizer with billable revenue in the window; a
 * value scopes to one. Idempotent: an organizer already invoiced for the exact
 * period is skipped.
 */
@Schema(name = "GenerateInvoicesRequest",
        description = "Generate commission invoices for an explicit billing period.")
public record GenerateInvoicesRequest(
        @Schema(description = "Limit to one organizer; null generates for all with billable revenue.",
                example = "7c9e6679-7425-40de-944b-e07fc1f90ae7", nullable = true)
        UUID organizerUuid,

        @Schema(description = "First day of the period (inclusive).", example = "2026-05-01")
        @NotNull(message = "periodStart is required")
        LocalDate periodStart,

        @Schema(description = "Last day of the period (inclusive).", example = "2026-05-31")
        @NotNull(message = "periodEnd is required")
        LocalDate periodEnd
) {
}
