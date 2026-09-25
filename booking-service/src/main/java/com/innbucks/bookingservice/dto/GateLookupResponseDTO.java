package com.innbucks.bookingservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A booking as gate staff need to see it when the customer has no QR: who
 * booked, and each ticket with its holder and whether it is already used.
 *
 * <p>Read-only. Admitting a holder is a normal {@code POST /tickets/scan}
 * with the chosen ticket's {@code ticketNumber}, so the single-shot claim,
 * the event-day rule and the {@code scan_attempts} audit are the same code
 * a QR scan runs — there is no second redemption path to keep in step.
 *
 * <p>Statuses mirror the scan's: anything other than {@code FOUND} carries
 * only the echoed confirmation number, so a refusal discloses nothing about
 * the booking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "GateLookupResponse")
public class GateLookupResponseDTO {

    @Schema(description = "Outcome of the lookup.")
    private Status status;

    @Schema(example = "INN-20260901-3C8849", description = "The normalised confirmation number looked up.")
    private String confirmationNumber;

    @Schema(example = "Tendai Ncube", nullable = true,
            description = "The purchaser's name. Present on FOUND. Null for bookings that pre-date names.")
    private String customerName;

    @Schema(example = "****4567", nullable = true,
            description = "Last four digits of the purchaser's phone — ask the customer for their number "
                          + "and compare. Present on FOUND when the booking has a phone.")
    private String customerPhoneLast4;

    @Schema(nullable = true, description = "Every ticket on the booking, ordered by ticket number so the list is stable between lookups. Present on FOUND.")
    private List<Ticket> tickets;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "GateLookupTicket")
    public static class Ticket {

        @Schema(example = "20260901-48291X",
                description = "Send this to POST /tickets/scan to admit the holder.")
        private String ticketNumber;

        private UUID bookingItemId;

        @Schema(example = "VIP")
        private String categoryName;

        @Schema(example = "Tendai Ncube", nullable = true,
                description = "The named attendee, else the purchaser — the person to let in on this ticket.")
        private String holderName;

        @Schema(example = "false", description = "True when this ticket has already been admitted.")
        private boolean redeemed;

        @Schema(nullable = true, description = "When it was admitted. Present only when redeemed.")
        private LocalDateTime redeemedAt;

        @Schema(example = "Tariro Chikomo", nullable = true,
                description = "Who admitted it. Present only when redeemed.")
        private String redeemedByName;
    }

    public enum Status {
        FOUND,
        BOOKING_NOT_FOUND,
        /** The booking exists but is PENDING or CANCELLED — not paid for. */
        BOOKING_NOT_CONFIRMED,
        WRONG_ORGANIZER,
        NOT_ASSIGNED_TO_EVENT
    }
}
