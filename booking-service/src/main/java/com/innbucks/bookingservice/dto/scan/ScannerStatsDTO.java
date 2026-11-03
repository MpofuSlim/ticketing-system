package com.innbucks.bookingservice.dto.scan;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Per-scanner stats for a window — surfaced at GET /scans/me/stats.
 *
 * <p>{@code byOutcome} is zero-filled with every possible Outcome enum value
 * so the FE never has to guard against an absent key (e.g. it can render
 * "0 wrong-organizer" without conditional logic). The order of the map is
 * the enum declaration order.
 *
 * <p>With {@code eventId} the stats are the scanner's at that one event: over
 * the window when one was asked for, else the whole event, and then
 * {@code from} and {@code to} are null.
 */
@Schema(name = "ScannerStats",
        description = "Outcome breakdown for one scanner over a window.")
public record ScannerStatsDTO(
        @Schema(example = "7e9a1c2b-4d5f-46a7-89b0-1c2d3e4f5a6b", nullable = true,
                description = "Stable user_uuid of the scanner. Null if the caller's token had no claim.")
        UUID scannerUserUuid,
        @Schema(example = "tariro@harare-arena.co.zw", nullable = true)
        String scannerEmail,
        @Schema(example = "Tariro Chikomo", nullable = true)
        String scannerDisplayName,
        @Schema(example = "2026-06-01T02:00:00+02:00", nullable = true,
                description = "Null when the stats cover the whole of eventId.") OffsetDateTime from,
        @Schema(example = "2026-06-30T23:59:59.999999999+02:00", nullable = true,
                description = "Null when the stats cover the whole of eventId.") OffsetDateTime to,
        @Schema(example = "412", description = "Total scan attempts across all outcomes.") long total,
        @Schema(description = "Count per Outcome enum value (zero-filled for missing keys).",
                example = "{\"ALLOWED\":380,\"ALREADY_REDEEMED\":24,\"WRONG_ORGANIZER\":2," +
                          "\"NOT_ASSIGNED_TO_EVENT\":0,\"TICKET_NOT_FOUND\":6,\"BOOKING_NOT_CONFIRMED\":0}")
        Map<String, Long> byOutcome,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", nullable = true,
                description = "The event the stats are for, when one was asked for. Absent otherwise.")
        UUID eventId
) {

    /** Window stats across every event, the shape before {@code eventId}. */
    public ScannerStatsDTO(UUID scannerUserUuid, String scannerEmail, String scannerDisplayName,
                           OffsetDateTime from, OffsetDateTime to, long total, Map<String, Long> byOutcome) {
        this(scannerUserUuid, scannerEmail, scannerDisplayName, from, to, total, byOutcome, null);
    }
}
