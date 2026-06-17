package com.innbucks.bookingservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Result of a ticket-scan attempt. Status drives the scanner-app UI:
 * <ul>
 *   <li>{@code ALLOWED} — green tick, customer walks in.</li>
 *   <li>{@code ALREADY_REDEEMED} — red toast with {@code redeemedAt} +
 *       {@code redeemedByName} so the gate-staff can decide whether to
 *       let them through manually ("scanned by Tariro at 19:42").</li>
 *   <li>{@code TICKET_NOT_FOUND} — bogus QR / typo in the ticket number.</li>
 *   <li>{@code BOOKING_NOT_CONFIRMED} — booking exists but is PENDING or
 *       CANCELLED, so the ticket isn't actually paid for yet.</li>
 *   <li>{@code WRONG_ORGANIZER} — the scanner doesn't work for this event's
 *       organizer (e.g. a TEAM_MEMBER scanned a ticket for someone else's
 *       event). Returned with no audit fields.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ScanTicketResponse")
public class ScanTicketResponseDTO {

    @Schema(description = "Outcome of the scan.")
    private Status status;

    @Schema(example = "20260619-48291X", description = "The ticket that was scanned (echoed back).")
    private String ticketNumber;

    @Schema(nullable = true, description = "Internal booking item id — included for any successful or " +
                                           "already-redeemed scan so the scanner UI can deep-link.")
    private UUID bookingItemId;

    @Schema(example = "2026-06-19T19:42:11", nullable = true,
            description = "When this ticket was first redeemed. Present on ALLOWED (the scan that just " +
                          "happened) and on ALREADY_REDEEMED (the earlier scan that won the race).")
    private LocalDateTime redeemedAt;

    @Schema(example = "Tariro Chikomo", nullable = true,
            description = "Display name of the team member / organizer who scanned. Same value " +
                          "regardless of whether the scanning user has since been disabled or " +
                          "renamed — denormalised at scan time so the audit trail is stable.")
    private String redeemedByName;

    public enum Status {
        ALLOWED,
        ALREADY_REDEEMED,
        TICKET_NOT_FOUND,
        BOOKING_NOT_CONFIRMED,
        WRONG_ORGANIZER
    }
}
