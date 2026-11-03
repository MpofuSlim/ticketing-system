package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** A team waiting for the signed-in person to accept. */
@Schema(name = "GateInvite")
public record GateInviteDTO(
        @Schema(example = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d") UUID scannerId,
        @Schema(example = "Harare Arena Events", nullable = true) String organizerName,
        @Schema(example = "Tendai Moyo", description = "The name the organizer typed for this person.") String name,
        @Schema(example = "2026-10-30T14:05:12+02:00") LocalDateTime invitedAt,
        @Schema(description = "What the organizer has assigned so far that has not ended. Can be empty.")
        List<ScannerEventDTO> events
) { }
