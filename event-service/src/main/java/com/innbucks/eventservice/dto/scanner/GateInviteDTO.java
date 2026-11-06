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
        @Schema(description = "Events to SCAN the organizer has assigned so far that have not ended. Empty when "
                + "canScan is false. Can be empty.")
        List<ScannerEventDTO> events,
        @Schema(example = "true", description = "The invite is to scan tickets at the gate.") boolean canScan,
        @Schema(example = "false", description = "The invite is to sell tickets (a sales agent).") boolean canSell,
        @Schema(description = "Events to SELL for that the organizer has assigned so far and that have not ended. "
                + "Empty when canSell is false. Can be empty.")
        List<ScannerEventDTO> salesEvents
) { }
