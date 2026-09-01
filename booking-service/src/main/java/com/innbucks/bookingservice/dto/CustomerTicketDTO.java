package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One confirmed booking as the customer's "my tickets" screen needs it —
 * the event it is for, when that event is, and the scannable QR for each seat.
 *
 * <p><b>PII-free by construction</b>, exactly like {@link PublicBookingResponseDTO}
 * and for the same reason: this is served from an unauthenticated endpoint.
 * It deliberately omits {@code userEmail}, {@code phoneNumber},
 * {@code tenantUserUuid} and the {@code pointsUsed}/{@code cashAmount} payment
 * split. Adding any of them back widens what an enumerating caller harvests —
 * don't, without also fixing the access control.
 *
 * <p>Note what it DOES carry: {@link BookingItemDTO#getQrCode()}, the scannable
 * entry instrument. That is the point of the screen, and it is also the reason
 * the access control on the endpoint serving this matters more than on an
 * ordinary read.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerTicketDTO {

    @Schema(description = "Booking id. Also the key for the public per-ticket QR/HTML endpoints "
            + "(`GET /bookings/{id}/tickets/{ticketNumber}/qr.png`).",
            example = "a3b9c1d2-1234-5678-9abc-def012345678")
    private UUID id;

    @Schema(description = "Human-facing confirmation reference.", example = "INN-20260502-AB12CD")
    private String confirmationNumber;

    @Schema(description = "Event the ticket is for.",
            example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID eventId;

    @Schema(description = "Event name. Null only if event-service could not be reached.",
            example = "Harare Jazz Festival")
    private String eventTitle;

    @Schema(description = "Event venue. Null only if event-service could not be reached.",
            example = "Harare International Conference Centre")
    private String venue;

    @Schema(description = "Event start (UTC, serialized with a trailing Z).",
            example = "2026-09-12T16:00:00Z")
    private LocalDateTime startDateTime;

    @Schema(description = "Event end (UTC, serialized with a trailing Z).",
            example = "2026-09-12T22:00:00Z")
    private LocalDateTime endDateTime;

    @Schema(description = "Which bucket this ticket falls in, decided by the SERVER in UTC. "
            + "Do not recompute this on the device — a wrong device clock would hide a live ticket.",
            example = "UPCOMING")
    private TicketWindow window;

    @Schema(description = "True when the event is running right now — a convenience mirror of "
            + "`window == LIVE`, so the app can float the current ticket to the top without "
            + "comparing enum values.",
            example = "false")
    private boolean live;

    @Schema(description = "Total paid for the booking.", example = "100.00")
    private BigDecimal totalAmount;

    @Schema(description = "The booked seats — each with its ticket number and scannable QR.")
    private List<BookingItemDTO> items;

    @Schema(description = "When the booking was made (UTC).", example = "2026-05-02T15:45:00Z")
    private LocalDateTime createdAt;
}
