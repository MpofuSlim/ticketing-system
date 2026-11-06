package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** An event the signed-in sales agent sells for, with their terms (V21). */
@Schema(name = "AgentSalesEvent")
public record AgentSalesEventDTO(
        @Schema(example = "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e",
                description = "The agent's team row on this organizer's team.") UUID agentId,
        @Schema(example = "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0") UUID eventId,
        @Schema(example = "Jazz on the Lawn") String title,
        @Schema(example = "Harare Gardens") String venue,
        @Schema(example = "2026-11-14T15:00:00+02:00") LocalDateTime startDateTime,
        @Schema(example = "2026-11-14T21:00:00+02:00") LocalDateTime endDateTime,
        @Schema(example = "Harare Arena Events", nullable = true) String organizerName,
        @Schema(example = "200", nullable = true, description = "Null = no cap.") Integer ticketCap,
        @Schema(example = "5.00") BigDecimal commissionPercent
) { }
