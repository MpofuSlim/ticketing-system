package com.innbucks.bookingservice.dto.scan;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Team-leaderboard payload for GET /scans/team-stats.
 *
 * <p>{@code members} is ordered by {@code total} DESC — the most active
 * scanner first so the dashboard can render the table without re-sorting.
 * Members with no scans in the window are absent from the list (an empty
 * leaderboard is the legitimate response on a quiet day).
 */
@Schema(name = "TeamStatsResponse",
        description = "Leaderboard of scan outcomes per team member for the calling organizer's gate staff.")
public record TeamStatsResponseDTO(
        @Schema(example = "2026-06-19T19:00:00+02:00") OffsetDateTime from,
        @Schema(example = "2026-06-20T23:59:59.999999999+02:00") OffsetDateTime to,
        List<TeamMemberStatsDTO> members
) { }
