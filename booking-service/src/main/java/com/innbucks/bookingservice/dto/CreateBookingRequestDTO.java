package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(name = "CreateBookingRequest")
public class CreateBookingRequestDTO {

    @Schema(example = "dc74382d-26ab-431d-b049-1c3a6d8dba51",
            description = "UUID of the event being booked.")
    @NotNull(message = "Event ID is required")
    private UUID eventId;

    // The purchaser's full name. Required for guest bookings. For an
    // authenticated customer the JWT's firstName/lastName is the FALLBACK
    // when this is absent — a value sent here always wins, so a customer
    // booking under a different display name than their profile can. The
    // controller resolves the final value and writes it back onto this
    // field before the service reads it.
    @Schema(example = "Alice Moyo", nullable = true,
            description = "The purchaser's full name. Required for guest (unauthenticated) bookings; for an "
                    + "authenticated customer it defaults to the name on their profile when omitted. "
                    + "Shown to the organizer as who bought the tickets.")
    @Size(max = 120, message = "Full name must be at most 120 characters")
    private String customerName;

    // Optional. Guest web bookings (no JWT) send userEmail and phoneNumber
    // here so the booking can be looked up later. When the request is
    // authenticated, the JWT's email/phone claims win and these fields are
    // ignored.
    @Schema(example = "alice@example.com", nullable = true,
            description = "Only required for guest (unauthenticated) bookings. Ignored when a JWT is present.")
    private String userEmail;

    @Schema(example = "+263771234567", nullable = true,
            description = "Only required for guest (unauthenticated) bookings. Ignored when a JWT is present.")
    private String phoneNumber;

    @Schema(description = "One entry per ticket to book, in the order the tickets should be issued. "
            + "Each may name the attendee who will hold that ticket.")
    @NotEmpty(message = "At least one seat is required")
    private List<@Valid SeatItemRequest> seats;

    // Each entry requests one ticket in the given category. GA model: the
    // service claims capacity per category; the ticket itself is fungible.
    @Data
    @Schema(name = "SeatItemRequest",
            description = "Requests one ticket in the given category, optionally naming who will hold it.")
    public static class SeatItemRequest {

        @Schema(example = "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                description = "UUID of the seat category (VIP, GA, etc.).")
        @NotNull(message = "Category ID is required")
        private UUID categoryId;

        // Optional. Omit for the purchaser's own ticket. When present, this
        // ticket is issued in the attendee's name; with a phone, its QR is
        // delivered to the ATTENDEE instead of the purchaser (falling back to
        // the purchaser only if that send fails).
        @Schema(nullable = true,
                description = "Optional attendee for THIS ticket. Omit for the purchaser's own ticket. When "
                        + "present, the ticket is issued in the attendee's name; with a `phoneNumber`, its "
                        + "WhatsApp QR goes to the attendee INSTEAD of the purchaser (purchaser fallback only "
                        + "if that send fails); an `email` additionally gets a one-ticket confirmation.")
        @Valid
        private AttendeeRequest attendee;
    }

    @Data
    @Schema(name = "AttendeeRequest",
            description = "Who will hold one ticket. `fullName` is required once the object is present; "
                    + "`phoneNumber` and `email` are optional but at least one is needed for the ticket to be "
                    + "delivered to the attendee directly.")
    public static class AttendeeRequest {

        @Schema(example = "Tendai Ncube", description = "The attendee's full name — printed on the ticket "
                + "and shown to the organizer and gate staff.")
        @NotBlank(message = "Attendee full name is required")
        @Size(max = 120, message = "Attendee full name must be at most 120 characters")
        private String fullName;

        @Schema(example = "tendai@example.com", nullable = true,
                description = "Optional. If given, the attendee receives a confirmation email for their ticket.")
        @Email(message = "Attendee email is not a valid email address")
        @Size(max = 255, message = "Attendee email must be at most 255 characters")
        private String email;

        // Validated + canonicalised to E.164 by the controller (same
        // MsisdnValidator path as the purchaser's phone). Stored normalised.
        @Schema(example = "+263772000000", nullable = true,
                description = "Optional. If given, the attendee receives their ticket's QR over WhatsApp. "
                        + "Validated like the purchaser's number: full international format, or a local "
                        + "number for this deployment's country.")
        @Size(max = 32, message = "Attendee phone number must be at most 32 characters")
        private String phoneNumber;
    }
}
