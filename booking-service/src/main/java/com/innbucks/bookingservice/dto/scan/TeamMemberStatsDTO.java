package com.innbucks.bookingservice.dto.scan;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

/**
 * One leaderboard row in {@link TeamStatsResponseDTO} — collapsed per
 * scanner_user_uuid. Zero-fill semantics on {@code byOutcome} match the
 * other stats DTOs.
 */
@Schema(name = "TeamMemberStats",
        description = "Outcome breakdown for one team member, an entry in the team-stats leaderboard.")
public record TeamMemberStatsDTO(
        @Schema(example = "7e9a1c2b-4d5f-46a7-89b0-1c2d3e4f5a6b")
        UUID scannerUserUuid,
        @Schema(example = "tariro@harare-arena.co.zw", nullable = true)
        String scannerEmail,
        @Schema(example = "Tariro Chikomo", nullable = true)
        String scannerDisplayName,
        @Schema(example = "412", description = "Total scan attempts across all outcomes.")
        long total,
        @Schema(description = "Count per Outcome enum value (zero-filled for missing keys).",
                example = "{\"ALLOWED\":380,\"ALREADY_REDEEMED\":24,\"WRONG_ORGANIZER\":2," +
                          "\"NOT_ASSIGNED_TO_EVENT\":0,\"TICKET_NOT_FOUND\":6,\"BOOKING_NOT_CONFIRMED\":0}")
        Map<String, Long> byOutcome
) { }
