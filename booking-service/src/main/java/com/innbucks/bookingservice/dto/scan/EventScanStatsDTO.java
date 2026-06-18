package com.innbucks.bookingservice.dto.scan;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Per-event scan stats for a window — surfaced at GET /scans/events/{id}/stats.
 *
 * <p>Same zero-fill contract as {@link ScannerStatsDTO#byOutcome()}.
 */
@Schema(name = "EventScanStats",
        description = "Outcome breakdown of scan attempts for a single event over a window.")
public record EventScanStatsDTO(
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID eventId,
        @Schema(example = "2026-06-19T17:00:00Z") Instant from,
        @Schema(example = "2026-06-20T02:00:00Z") Instant to,
        @Schema(example = "1827", description = "Total scan attempts for the event across all outcomes.") long total,
        @Schema(description = "Count per Outcome enum value (zero-filled for missing keys).",
                example = "{\"ALLOWED\":1742,\"ALREADY_REDEEMED\":63,\"WRONG_ORGANIZER\":4," +
                          "\"NOT_ASSIGNED_TO_EVENT\":1,\"TICKET_NOT_FOUND\":17,\"BOOKING_NOT_CONFIRMED\":0}")
        Map<String, Long> byOutcome
) { }
