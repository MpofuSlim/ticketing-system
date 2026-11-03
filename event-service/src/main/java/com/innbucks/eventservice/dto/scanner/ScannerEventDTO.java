package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/** An event a scanner is assigned to, as the portal and an invite show it. */
@Schema(name = "ScannerEvent")
public record ScannerEventDTO(
        @Schema(example = "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0") UUID eventId,
        @Schema(example = "Peterhouse Sixth Form Ball") String title,
        @Schema(example = "Peterhouse, Marondera") String venue,
        @Schema(example = "2026-11-07T18:00:00+02:00") LocalDateTime startDateTime,
        @Schema(example = "2026-11-08T01:00:00+02:00") LocalDateTime endDateTime
) { }
