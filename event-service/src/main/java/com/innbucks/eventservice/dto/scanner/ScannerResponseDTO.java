package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A scanner as the organizer sees it. Never says whether the number has an
 * InnBucks account: every new scanner is INVITED until the person accepts.
 */
@Schema(name = "Scanner")
public record ScannerResponseDTO(
        @Schema(example = "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e") UUID scannerId,
        @Schema(example = "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b",
                description = "The owning organizer (users.user_uuid).") UUID organizerUuid,
        @Schema(example = "Tendai") String firstName,
        @Schema(example = "Moyo") String lastName,
        @Schema(example = "+263771234567") String phoneNumber,
        @Schema(example = "INVITED", allowableValues = {"INVITED", "ACTIVE", "DECLINED", "DISABLED"})
        String status,
        @Schema(description = "Assigned events that have not been deleted, soonest first.")
        List<ScannerEventDTO> events,
        @Schema(example = "2026-10-30T14:05:12+02:00") LocalDateTime createdAt
) { }
