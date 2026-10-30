package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AttendeeUpdateRequestDTO;
import com.innbucks.bookingservice.dto.AttendeeUpdateResponseDTO;
import com.innbucks.bookingservice.service.AttendeeEditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
@Tag(name = "Ticket Resend",
     description = "Manual re-delivery of a confirmed booking's e-tickets, and correcting a ticket's attendee.")
@SecurityRequirement(name = "bearerAuth")
public class AttendeeController {

    private final AttendeeEditService attendeeEditService;

    @PutMapping("/{bookingId}/tickets/{ticketNumber}/attendee")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_MANAGER')")
    @Operation(summary = "Correct one ticket's named attendee (by ticket number)", deprecated = true,
            description = """
                    **Use `PUT /bookings/{bookingId}/items/{bookingItemId}/attendee` instead.** This form
                    puts the ticket number (the gate credential) in the URL, where every access log on the
                    way keeps it, and a masked guest list cannot address a ticket by number. It keeps
                    working until the console has moved.

                    Replaces the attendee on one ticket: `fullName` is required; an omitted or blank
                    `email` / `phoneNumber` CLEARS it, so send the whole attendee. The phone is validated
                    and stored in E.164 exactly as at booking.

                    **Sends nothing.** To deliver the ticket to the corrected contact, call
                    `POST /bookings/{id}/resend-ticket` afterwards.

                    **SUPER_ADMIN** and **PRODUCT_MANAGER** may edit any booking; an **EVENT_ORGANIZER**
                    only bookings of its own events. A CANCELLED booking and a ticket already used at the
                    gate are refused. `changed` lists the fields that actually changed (empty = none).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attendee updated",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Attendee updated",
                              "data": {
                                "bookingId": "2a44b230-a61b-400e-b0fc-2cbadb59ba5a",
                                "confirmationNumber": "INN-20261020-3BD788",
                                "ticketNumber": "20261020-97554W",
                                "attendeeName": "Joel Mandingaisa",
                                "attendeeEmail": "joel@example.com",
                                "attendeePhone": "+263713664366",
                                "holderName": "Joel Mandingaisa",
                                "changed": ["phoneNumber"]
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "Invalid input, cancelled booking or used ticket",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Bad phone", value = """
                                    { "code": "400 BAD_REQUEST", "message": "That phone number doesn't look valid. Please enter it in full international format, e.g. +263772000000.", "data": null }
                                    """),
                            @ExampleObject(name = "Cancelled booking", value = """
                                    { "code": "400 BAD_REQUEST", "message": "A cancelled booking's attendees cannot be changed", "data": null }
                                    """),
                            @ExampleObject(name = "Ticket already used", value = """
                                    { "code": "400 BAD_REQUEST", "message": "This ticket has already been used at the gate", "data": null }
                                    """)})),
            @ApiResponse(responseCode = "403", description = "Not the booking's event owner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "You do not own this booking's event", "data": null }
                            """))),
            @ApiResponse(responseCode = "404", description = "Booking, or ticket on it, not found",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No booking", value = """
                                    { "code": "404 NOT_FOUND", "message": "Booking not found", "data": null }
                                    """),
                            @ExampleObject(name = "No such ticket", value = """
                                    { "code": "404 NOT_FOUND", "message": "Ticket not found on this booking", "data": null }
                                    """)}))
    })
    public ResponseEntity<ApiResult<AttendeeUpdateResponseDTO>> updateAttendee(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @PathVariable String ticketNumber,
            @Valid @RequestBody AttendeeUpdateRequestDTO request) {
        return ResponseEntity.ok(ApiResult.ok("Attendee updated",
                attendeeEditService.update(authentication, bookingId, ticketNumber, request)));
    }

    @PutMapping("/{bookingId}/items/{bookingItemId}/attendee")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_MANAGER')")
    @Operation(summary = "Correct one ticket's named attendee",
            description = """
                    The ticket is addressed by its `bookingItemId` (on every guest-list row and on each
                    item of a booking read by staff), never by its ticket number.

                    Replaces the attendee on one ticket: `fullName` is required; an omitted or blank
                    `email` / `phoneNumber` CLEARS it, so send the whole attendee. The phone is validated
                    and stored in E.164 exactly as at booking.

                    **Sends nothing.** To deliver the ticket to the corrected contact, call
                    `POST /bookings/{id}/resend-ticket` afterwards.

                    **SUPER_ADMIN** and **PRODUCT_MANAGER** may edit any booking; an **EVENT_ORGANIZER**
                    only bookings of its own events. A CANCELLED booking and a ticket already used at the
                    gate are refused. `changed` lists the fields that actually changed (empty = none).
                    `ticketNumber` in the answer is masked where the cell masks ticket numbers.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attendee updated",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Attendee updated",
                              "data": {
                                "bookingId": "2a44b230-a61b-400e-b0fc-2cbadb59ba5a",
                                "confirmationNumber": "INN-20261020-3BD788",
                                "ticketNumber": "20261020-****4W",
                                "attendeeName": "Joel Mandingaisa",
                                "attendeeEmail": "joel@example.com",
                                "attendeePhone": "+263713664366",
                                "holderName": "Joel Mandingaisa",
                                "changed": ["phoneNumber"]
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "Invalid input, cancelled booking or used ticket",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Bad phone", value = """
                                    { "code": "400 BAD_REQUEST", "message": "That phone number doesn't look valid. Please enter it in full international format, e.g. +263772000000.", "data": null }
                                    """),
                            @ExampleObject(name = "Cancelled booking", value = """
                                    { "code": "400 BAD_REQUEST", "message": "A cancelled booking's attendees cannot be changed", "data": null }
                                    """),
                            @ExampleObject(name = "Ticket already used", value = """
                                    { "code": "400 BAD_REQUEST", "message": "This ticket has already been used at the gate", "data": null }
                                    """)})),
            @ApiResponse(responseCode = "403", description = "Not the booking's event owner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "You do not own this booking's event", "data": null }
                            """))),
            @ApiResponse(responseCode = "404", description = "Booking, or that ticket on it, not found",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No booking", value = """
                                    { "code": "404 NOT_FOUND", "message": "Booking not found", "data": null }
                                    """),
                            @ExampleObject(name = "No such ticket", value = """
                                    { "code": "404 NOT_FOUND", "message": "Ticket not found on this booking", "data": null }
                                    """)}))
    })
    public ResponseEntity<ApiResult<AttendeeUpdateResponseDTO>> updateAttendeeByItem(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @PathVariable UUID bookingItemId,
            @Valid @RequestBody AttendeeUpdateRequestDTO request) {
        return ResponseEntity.ok(ApiResult.ok("Attendee updated",
                attendeeEditService.updateByItem(authentication, bookingId, bookingItemId, request)));
    }
}
