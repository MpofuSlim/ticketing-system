package com.innbucks.bookingservice.dto;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.service.TicketDeliveryService;

import java.util.UUID;

/**
 * Per-channel result of a manual e-ticket resend, so the dashboard can show
 * the operator exactly what went out (e.g. "email sent, WhatsApp 2/2").
 * {@code emailAttempted}/{@code whatsappAttempted} are false when the booking
 * simply has no address/phone for that channel — not a failure.
 */
public record TicketResendResponseDTO(
        UUID bookingId,
        String confirmationNumber,
        boolean emailAttempted,
        boolean emailSent,
        boolean whatsappAttempted,
        int qrTicketsSent,
        int qrTicketsTotal) {

    public static TicketResendResponseDTO from(Booking booking, TicketDeliveryService.Outcome o) {
        return new TicketResendResponseDTO(
                booking.getId(),
                booking.getConfirmationNumber(),
                o.emailAttempted(),
                o.emailSent(),
                o.whatsappAttempted(),
                o.qrTicketsSent(),
                o.qrTicketsTotal());
    }
}
