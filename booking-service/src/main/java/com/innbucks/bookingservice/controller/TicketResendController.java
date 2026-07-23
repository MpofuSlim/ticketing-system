package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TicketResendResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import com.innbucks.bookingservice.service.TicketDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Manual e-ticket redelivery for the organizer/admin dashboard — the "customer
 * says the WhatsApp never arrived" button. Re-runs the exact same delivery the
 * confirmation listener performs ({@link TicketDeliveryService}): plain-text
 * email if the booking has one, one WhatsApp QR template send per ticket if it
 * has a phone. Idempotent from the platform's perspective (delivery only; no
 * booking state changes), so repeated clicks are safe — each one just sends
 * again.
 */
@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Ticket Resend",
     description = "Manual re-delivery of a confirmed booking's e-tickets (email + WhatsApp QR).")
@SecurityRequirement(name = "bearerAuth")
public class TicketResendController {

    private final BookingRepository bookingRepository;
    private final TicketDeliveryService ticketDeliveryService;

    @PostMapping("/{id}/resend-ticket")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Resend a confirmed booking's e-tickets",
            description = """
                    Re-delivers the booking's tickets over every channel the booking has an
                    address for: the plain-text confirmation email (if `userEmail` is set) and
                    one WhatsApp QR e-ticket per ticket (if `phoneNumber` is set). Exactly the
                    same sends the customer got at confirmation — for when a customer reports
                    the WhatsApp/email never arrived.

                    Only **CONFIRMED** bookings can be resent (a PENDING booking hasn't paid;
                    a CANCELLED one must not receive gate-scannable tickets).

                    **EVENT_ORGANIZER** may resend only bookings of their own events
                    (`booking.tenantUserUuid` must match the caller's organizer id);
                    **SUPER_ADMIN** may resend any booking. Delivery is best-effort per
                    channel — the response reports what was attempted and what succeeded, so
                    the dashboard can show e.g. "email sent, WhatsApp 2/2".
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Delivery re-attempted; per-channel results in data",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Resent", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Ticket delivery re-attempted",
                                      "data": {
                                        "bookingId": "8d2c3e4a-1f5b-46a7-8c9d-0e1f2a3b4c5d",
                                        "confirmationNumber": "INN-20260723-09BB20",
                                        "emailAttempted": true,
                                        "emailSent": true,
                                        "whatsappAttempted": true,
                                        "qrTicketsSent": 1,
                                        "qrTicketsTotal": 1
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Booking is not CONFIRMED",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "400 BAD_REQUEST", "message": "Only CONFIRMED bookings can have tickets resent (status: CANCELLED)", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller does not own the booking's event (or holds neither EVENT_ORGANIZER nor SUPER_ADMIN)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "You do not own this booking's event", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Booking not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Booking not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<TicketResendResponseDTO>> resendTicket(
            Authentication authentication,
            @PathVariable UUID id) {
        Booking booking = bookingRepository.findByIdWithItems(id)
                .orElseThrow(() -> new NotFoundException("Booking not found"));

        requireOwnershipUnlessAdmin(authentication, booking);

        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            throw new BadRequestException("Only CONFIRMED bookings can have tickets resent (status: "
                    + booking.getStatus() + ")");
        }

        log.info("Manual ticket resend requested bookingId={} ref={} by={}",
                booking.getId(), booking.getConfirmationNumber(), authentication.getName());
        TicketDeliveryService.Outcome outcome = ticketDeliveryService.deliver(booking);

        return ResponseEntity.ok(ApiResult.ok("Ticket delivery re-attempted",
                TicketResendResponseDTO.from(booking, outcome)));
    }

    /**
     * SUPER_ADMIN may resend anything; an EVENT_ORGANIZER only bookings whose
     * {@code tenantUserUuid} (stamped at creation from the owning event) equals
     * their own organizer id. A booking with a null tenant stamp fails closed
     * for organizers — same posture as the analytics ownership checks.
     */
    private static void requireOwnershipUnlessAdmin(Authentication authentication, Booking booking) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        if (admin) {
            return;
        }
        UUID organizerUuid = AuthenticatedCaller.organizerUuid(authentication);
        if (organizerUuid == null || booking.getTenantUserUuid() == null
                || !organizerUuid.equals(booking.getTenantUserUuid())) {
            throw new AccessDeniedException("You do not own this booking's event");
        }
    }
}
