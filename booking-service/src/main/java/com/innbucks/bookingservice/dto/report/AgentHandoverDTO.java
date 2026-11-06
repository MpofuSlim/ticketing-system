package com.innbucks.bookingservice.dto.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Cash an organizer recorded receiving from a sales agent (V36). The agent's
 * own report shows only live handovers and never who recorded them; the
 * organizer's list shows soft-deleted ones too, with who deleted them.
 */
@Schema(name = "AgentHandover", description = "Cash a sales agent handed over to the organiser")
public record AgentHandoverDTO(
        @Schema(example = "6d0f3c2b-8a41-4e7d-9b1c-2f5a7e9d0c11") UUID id,
        @Schema(description = "The sales agent (their event-team row)", example = "4b1e2c3d-5f60-4a7b-8c9d-0e1f2a3b4c5d")
        UUID agentId,
        @Schema(description = "The event the cash was for; null = no event in particular", nullable = true,
                example = "5772554b-1f0e-4c1d-9a7e-3b2c1d0e9f8a")
        UUID eventId,
        @Schema(example = "1500.00") BigDecimal amount,
        @Schema(example = "USD") String currency,
        @Schema(description = "When the cash changed hands") LocalDateTime receivedAt,
        @Schema(description = "Who recorded it (organiser and staff views only)", nullable = true,
                example = "organizer@example.com")
        @JsonInclude(JsonInclude.Include.NON_NULL) String recordedBy,
        @Schema(example = "Friday cash", nullable = true) String note,
        @Schema(description = "When it was recorded") LocalDateTime createdAt,
        @Schema(description = "Set when the organiser deleted it; deleted handovers count in no total",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime deletedAt,
        @Schema(description = "Who deleted it", nullable = true, example = "organizer@example.com")
        @JsonInclude(JsonInclude.Include.NON_NULL) String deletedBy) {
}
