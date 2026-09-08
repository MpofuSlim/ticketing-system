package com.innbucks.bookingservice.dto;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.service.TicketDeliveryService;

import java.util.UUID;

/**
 * Per-channel result of a manual e-ticket resend, so the dashboard can show
 * the operator exactly what went out (e.g. "email sent, WhatsApp 2/2,
 * attendees 1/1"). {@code emailAttempted}/{@code whatsappAttempted} are false
 * when the booking simply has no address/phone for that channel — not a
 * failure.
 *
 * <p>The {@code attendee*} counters (V22) cover the direct-to-attendee sends:
 * one per ticket that carries an attendee phone/email distinct from the
 * purchaser's. {@code attendeeDeliveriesTotal} is 0 when no ticket names a
 * contactable attendee.
 */
public record TicketResendResponseDTO(
        UUID bookingId,
        String confirmationNumber,
        boolean emailAttempted,
        boolean emailSent,
        boolean whatsappAttempted,
        int qrTicketsSent,
        int qrTicketsTotal,
        int attendeeDeliveriesSent,
        int attendeeDeliveriesTotal) {

    public static TicketResendResponseDTO from(Booking booking, TicketDeliveryService.Outcome o) {
        return new TicketResendResponseDTO(
                booking.getId(),
                booking.getConfirmationNumber(),
                o.emailAttempted(),
                o.emailSent(),
                o.whatsappAttempted(),
                o.qrTicketsSent(),
                o.qrTicketsTotal(),
                o.attendeeDeliveriesSent(),
                o.attendeeDeliveriesTotal());
    }
}
