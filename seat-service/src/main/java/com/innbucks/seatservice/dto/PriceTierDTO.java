package com.innbucks.seatservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One price tier as read back. {@code sold}, {@code remaining} and
 * {@code status} are present on the category listing and the write responses;
 * the booking read ({@code GET /seat-categories/{id}}) carries the terms only,
 * because booking-service counts what was sold itself, under its stock lock.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "PriceTier")
public class PriceTierDTO {

    /** Whether a tier is the price now, will be later, or is over. */
    public enum Status { ACTIVE, UPCOMING, ENDED, SOLD_OUT }

    @Schema(example = "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f")
    private UUID id;

    @Schema(example = "Early bird")
    private String name;

    @Schema(example = "100.00")
    private BigDecimal price;

    @Schema(example = "2026-09-08T23:59:59+02:00", nullable = true,
            description = "When the tier closes, at the market's offset; null when it closes on quantity alone.")
    private LocalDateTime endsAt;

    @Schema(example = "100", nullable = true,
            description = "Tickets that sell at this tier; null when it closes on time alone.")
    private Integer quantity;

    @Schema(example = "37", nullable = true,
            description = "Tickets held or sold at this tier. Absent on the booking read, and when "
                    + "booking-service could not be asked.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long sold;

    @Schema(example = "63", nullable = true,
            description = "quantity minus sold, never below 0. Absent without a quantity or a count.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long remaining;

    @Schema(example = "ACTIVE",
            description = "ACTIVE: the price now. UPCOMING: applies once the tiers before it close. "
                    + "ENDED: past endsAt. SOLD_OUT: quantity reached (it reopens if a hold on it expires "
                    + "before endsAt).")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Status status;
}
