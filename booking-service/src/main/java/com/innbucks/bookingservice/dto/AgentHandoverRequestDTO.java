package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/** Cash the organizer received from a sales agent (V36). */
@Data
@Schema(name = "AgentHandoverRequest", description = "Cash received from a sales agent")
public class AgentHandoverRequestDTO {

    @Schema(example = "1500.00", description = "The amount received, more than 0, at most 2 decimals.")
    @NotNull(message = "Please enter the amount received.")
    @DecimalMin(value = "0.01", message = "The amount must be more than 0.")
    @Digits(integer = 10, fraction = 2, message = "The amount can have at most 2 decimals.")
    private BigDecimal amount;

    @Schema(nullable = true, example = "5772554b-1f0e-4c1d-9a7e-3b2c1d0e9f8a",
            description = "The event the cash was for: one of yours the agent sold for. Leave it out for no "
                    + "event in particular. A handover recorded against an event counts in that event's figures "
                    + "only; one with no event counts in the reports that are not filtered by event.")
    private UUID eventId;

    @Schema(nullable = true, example = "2026-11-14T18:30:00",
            description = "When the cash changed hands, as the organiser's local time (no zone), or an exact "
                    + "instant with Z or an offset. Leave it out for now. Not in the future.")
    private String receivedAt;

    @Schema(nullable = true, example = "Friday cash", description = "Optional, at most 500 characters.")
    @Size(max = 500, message = "The note can be at most 500 characters.")
    private String note;
}
