package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Provenance for a report response: the window it actually covers and the scope
 * it was computed over.
 *
 * <p><b>Why it exists.</b> Every report endpoint defaults its window when
 * {@code from}/{@code to} are omitted, so the numbers in the body describe a
 * period the caller never named. Exported to a spreadsheet they lose that
 * context entirely and become a column of figures about nothing in particular.
 * This lets a client stamp the period and scope onto the file.
 *
 * <p><b>The dates are the RESOLVED window, not the request's.</b> They come from
 * the same {@code resolveRange} the query uses, so they cannot drift from the
 * data — which is the whole point. Reporting the raw request parameters would
 * put "null to null" on a report covering the last 30 days.
 */
@Schema(name = "ReportMeta", description = "What period and scope this report covers.")
public record ReportMetaDTO(

        @Schema(description = "First day included, inclusive. The resolved value — "
                + "present even when the request omitted it.", example = "2026-08-11")
        LocalDate from,

        @Schema(description = "Last day included, inclusive.", example = "2026-09-09")
        LocalDate to,

        @Schema(description = "Event the report is filtered to, or null for every event "
                + "in the organizer's scope.", nullable = true,
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID eventId,

        @Schema(description = "Whose data this covers: the organizer's uuid, or null when "
                + "the caller is platform staff and the report spans every organizer.",
                nullable = true, example = "9c1b2f3a-4d5e-6f70-8192-a3b4c5d6e7f8")
        UUID organizerUuid,

        @Schema(description = "True when the report spans every organizer — platform staff "
                + "only. Explicit rather than inferred from a null organizerUuid, so a "
                + "client never has to guess what the null meant.", example = "false")
        boolean platformWide
) {
    public static ReportMetaDTO of(LocalDate from, LocalDate to, UUID eventId, UUID organizerUuid) {
        return new ReportMetaDTO(from, to, eventId, organizerUuid, organizerUuid == null);
    }
}
