package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/** An event the signed-in person may scan, now or later. */
@Schema(name = "GateEvent")
public record GateEventDTO(
        @Schema(example = "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e") UUID scannerId,
        @Schema(example = "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0") UUID eventId,
        @Schema(example = "Peterhouse Sixth Form Ball") String title,
        @Schema(example = "Peterhouse, Marondera") String venue,
        @Schema(example = "2026-11-07T18:00:00+02:00") LocalDateTime startDateTime,
        @Schema(example = "2026-11-08T01:00:00+02:00") LocalDateTime endDateTime,
        @Schema(example = "UPCOMING", allowableValues = {"UPCOMING", "LIVE"},
                description = "LIVE between the event's start and end.") String status,
        @Schema(example = "Peterhouse Group of Schools", nullable = true) String organizerName
) { }
