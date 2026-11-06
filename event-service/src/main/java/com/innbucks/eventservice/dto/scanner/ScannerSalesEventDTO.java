package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** An event a sales agent sells for, with the organizer's terms, as the portal shows it (V21). */
@Schema(name = "ScannerSalesEvent")
public record ScannerSalesEventDTO(
        @Schema(example = "0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f") UUID eventId,
        @Schema(example = "Jazz on the Lawn") String title,
        @Schema(example = "Harare Gardens") String venue,
        @Schema(example = "2026-11-14T15:00:00+02:00") LocalDateTime startDateTime,
        @Schema(example = "2026-11-14T21:00:00+02:00") LocalDateTime endDateTime,
        @Schema(example = "200", nullable = true, description = "Null = no cap.") Integer ticketCap,
        @Schema(example = "5.00", description = "Percent of what the buyer paid.") BigDecimal commissionPercent
) { }
