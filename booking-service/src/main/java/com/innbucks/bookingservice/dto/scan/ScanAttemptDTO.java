package com.innbucks.bookingservice.dto.scan;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * FE-facing projection of a {@code scan_attempts} row.
 *
 * <p>Deliberately omits {@code clientIp}, {@code userAgent} and
 * {@code deviceId} from the entity — those are server-side
 * fingerprinting bits we keep for the fraud-signals follow-up and
 * incident response, not for the gate-staff dashboard.
 */
@Schema(name = "ScanAttempt", description = "One ticket-scan attempt and its outcome.")
public record ScanAttemptDTO(
        @Schema(example = "9b1f3c2e-6a47-4f7c-9d2b-1d6f0a1e5b91",
                description = "Audit row id (scan_attempts.id).")
        UUID id,
        @Schema(example = "2026-06-19T19:42:11Z",
                description = "When the scan happened, UTC.")
        Instant attemptedAt,
        @Schema(example = "ALLOWED",
                description = "Outcome of the scan. One of ALLOWED, ALREADY_REDEEMED, WRONG_ORGANIZER, " +
                              "NOT_ASSIGNED_TO_EVENT, TICKET_NOT_FOUND, BOOKING_NOT_CONFIRMED.")
        String outcome,
        @Schema(example = "20260619-48291X",
                description = "The ticket number that was scanned (echoed; could be a typo for the not-found case).")
        String ticketNumber,
        @Schema(example = "f1c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                description = "Booking item the ticket resolved to. Null when ticket lookup failed.")
        UUID bookingItemId,
        @Schema(example = "8d2c3e4a-1f5b-46a7-8c9d-0e1f2a3b4c5d", nullable = true,
                description = "Booking the item belonged to. Null when ticket lookup failed.")
        UUID bookingId,
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", nullable = true,
                description = "Event the booking was for. Null when ticket lookup failed.")
        UUID eventId,
        @Schema(example = "tariro@harare-arena.co.zw", nullable = true,
                description = "Login email of the scanner — denormalised at scan time so it survives email rotation.")
        String scannerEmail,
        @Schema(example = "Tariro Chikomo", nullable = true,
                description = "Human display name of the scanner — same value as redeemed_by_name on the happy path.")
        String scannerDisplayName,
        @Schema(example = "7e9a1c2b-4d5f-46a7-89b0-1c2d3e4f5a6b", nullable = true,
                description = "Stable user_uuid of the scanner. Null for legacy tokens with no claim.")
        UUID scannerUserUuid
) { }
