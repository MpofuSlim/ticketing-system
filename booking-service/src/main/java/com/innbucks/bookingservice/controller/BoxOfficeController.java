package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.BoxOfficeVoidRequestDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.idempotency.IdempotencyFilter;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import com.innbucks.bookingservice.service.BoxOfficeService;
import com.innbucks.bookingservice.util.MsisdnMasking;
import com.innbucks.bookingservice.util.MsisdnValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The box office (V35): the event's organizer sells tickets for cash from the
 * Foundry portal, and may void a sale. Rides the gateway's {@code /bookings/**}
 * route. Only an EVENT_ORGANIZER account, for its own events (owner decision,
 * 2026-11-03): its login has a second factor, and the cash-up report
 * reconciles by the account that sold.
 */
@Slf4j
@RestController
@RequestMapping("/bookings/box-office")
@PreAuthorize("hasRole('EVENT_ORGANIZER')")
@Tag(name = "Box office", description = "Cash sales at the organiser's outlet, and voids")
public class BoxOfficeController {

    static final String SALE_EXAMPLE = """
            {
              "code": "201 CREATED",
              "message": "Cash sale recorded",
              "data": {
                "id": "9b2e4c1a-7d3f-4e5a-8b6c-1a2b3c4d5e6f",
                "customerName": "Rufaro Moyo",
                "phoneNumber": "+263771234567",
                "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "confirmationNumber": "INN-20261120-4F2A9C",
                "status": "CONFIRMED",
                "totalAmount": 300.00,
                "cashAmount": 300.00,
                "paymentMethod": "CASH",
                "paymentMethodLabel": "Cash (box office)",
                "soldBy": "organizer@example.com",
                "items": [ { "categoryName": "VIP", "priceAtBooking": 150.00, "ticketNumber": "20261120-*****-**X2B" } ]
              }
            }""";
    static final String EXAMPLE_KEY_REQUIRED = """
            { "code": "400 BAD_REQUEST", "message": "A box office sale needs an Idempotency-Key header, so a double click can't sell twice.", "data": { "errorCode": "IDEMPOTENCY_KEY_REQUIRED" } }""";
    static final String EXAMPLE_ALREADY_VOIDED = """
            { "code": "409 CONFLICT", "message": "This sale has already been voided.", "data": { "errorCode": "ALREADY_VOIDED" } }""";
    static final String EXAMPLE_TICKET_SCANNED = """
            { "code": "409 CONFLICT", "message": "A ticket of this sale has already been scanned, so the sale can't be voided.", "data": { "errorCode": "TICKET_SCANNED" } }""";
    static final String EXAMPLE_EVENT_STARTED = """
            { "code": "409 CONFLICT", "message": "The event has started, so this sale can't be voided.", "data": { "errorCode": "EVENT_STARTED" } }""";
    static final String EXAMPLE_NOT_A_CASH_SALE = """
            { "code": "409 CONFLICT", "message": "Only a box office cash sale can be voided.", "data": { "errorCode": "NOT_A_CASH_SALE" } }""";
    private static final String FORBIDDEN = """
            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }""";

    private final BoxOfficeService boxOffice;
    private final String deploymentCountry;

    public BoxOfficeController(BoxOfficeService boxOffice,
                               @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.boxOffice = boxOffice;
        this.deploymentCountry = deploymentCountry;
    }

    @PostMapping
    @Operation(summary = "Sell tickets for cash",
            description = "The event's organiser records a cash sale at their outlet. The body is POST /bookings's: "
                    + "the BUYER's name, phone (required, the tickets go to it) and email, the tickets, attendees, "
                    + "answers, add-ons, promo code and accepted terms version. Every check an online sale gets "
                    + "applies, and the same refusals come back (SALES_NOT_STARTED, TERMS_NOT_ACCEPTED, "
                    + "ADD_ON_SOLD_OUT, sold out, ...). The booking is CONFIRMED at once, paid in CASH, with the "
                    + "signed-in account as its seller, and the tickets go to the buyer exactly as for an online "
                    + "sale. Requires an Idempotency-Key header: a retry with the same key gets the first answer, "
                    + "never a second sale.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sold",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Sold",
                            value = SALE_EXAMPLE))),
            @ApiResponse(responseCode = "400", description = "No Idempotency-Key, a missing buyer detail, or any "
                    + "refusal an online sale gives",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No Idempotency-Key", value = EXAMPLE_KEY_REQUIRED),
                            @ExampleObject(name = "No phone", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Please provide the buyer's phone number: the tickets are sent to it.", "data": null }""")
                    })),
            @ApiResponse(responseCode = "403", description = "Not an organiser, or not this event's",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Unknown or unpublished event",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = """
                                    { "code": "404 NOT_FOUND", "message": "Event not found", "data": { "errorCode": "EVENT_NOT_FOUND" } }"""))),
            @ApiResponse(responseCode = "409", description = "Any conflict an online sale gives (sold out, sales "
                    + "not started, event ended, an add-on sold out, terms changed), or the same Idempotency-Key "
                    + "still in progress"),
            @ApiResponse(responseCode = "503", description = "The event could not be checked just now",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Retry",
                            value = """
                                    { "code": "503 SERVICE_UNAVAILABLE", "message": "We couldn't check this event just now. Please try again.", "data": { "errorCode": "TRY_AGAIN" } }""")))
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> sell(
            @Parameter(description = "Any unique value per sale, e.g. a UUID made when the sale form opens")
            @RequestHeader(value = IdempotencyFilter.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequestDTO request, Authentication authentication) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "A box office sale needs an Idempotency-Key header, so a double click can't sell twice.");
        }
        // The BUYER's details come from the body only: the token is the organizer's.
        String phone = trimToNull(request.getPhoneNumber());
        if (phone == null) {
            throw new BadRequestException("Please provide the buyer's phone number: the tickets are sent to it.");
        }
        phone = MsisdnValidator.normalizeToE164(phone, deploymentCountry)
                .orElseThrow(() -> new BadRequestException("That phone number doesn't look valid. Please enter it "
                        + "in full international format, e.g. +263772000000."));
        String customerName = trimToNull(request.getCustomerName());
        if (customerName == null) {
            throw new BadRequestException("Please provide the buyer's full name.");
        }
        request.setCustomerName(customerName);
        request.setPhoneNumber(phone);
        String email = trimToNull(request.getUserEmail());
        request.setUserEmail(email);
        for (CreateBookingRequestDTO.SeatItemRequest seat : request.getSeats()) {
            CreateBookingRequestDTO.AttendeeRequest attendee = seat.getAttendee();
            if (attendee == null) {
                continue;
            }
            String fullName = trimToNull(attendee.getFullName());
            attendee.setFullName(fullName);
            attendee.setEmail(trimToNull(attendee.getEmail()));
            String attendeePhone = trimToNull(attendee.getPhoneNumber());
            attendee.setPhoneNumber(attendeePhone == null ? null : MsisdnValidator
                    .normalizeToE164(attendeePhone, deploymentCountry)
                    .orElseThrow(() -> new BadRequestException("The phone number for attendee \"" + fullName
                            + "\" doesn't look valid. Please enter it in full international format, e.g. "
                            + "+263772000000.")));
        }
        log.info("POST /bookings/box-office eventId={} seats={} buyer={}", request.getEventId(),
                request.getSeats().size(), MsisdnMasking.mask(phone));
        BookingResponseDTO sold = boxOffice.sell(request, email, phone,
                AuthenticatedCaller.organizerUuid(authentication), AuthenticatedCaller.userUuid(authentication),
                authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created("Cash sale recorded", sold));
    }

    @PostMapping("/{bookingId}/void")
    @Operation(summary = "Void a cash sale",
            description = "Cancels a box office cash sale: its tickets stop working at the gate, and its seats, "
                    + "add-ons and promo-code use go back on sale. Its assigned numbers are never given again. "
                    + "Only the event's organiser, only a cash sale, only before the event starts and before any "
                    + "of its tickets is scanned. The buyer is told the tickets are cancelled and to see the box "
                    + "office about their money. Shown in the cash-up report with who voided it and why.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voided",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Voided",
                            value = """
                                    { "code": "200 OK", "message": "Sale voided", "data": { "id": "9b2e4c1a-7d3f-4e5a-8b6c-1a2b3c4d5e6f", "confirmationNumber": "INN-20261120-4F2A9C", "status": "CANCELLED", "totalAmount": 300.00, "paymentMethod": "CASH", "soldBy": "organizer@example.com", "voidedBy": "organizer@example.com", "voidedAt": "2026-11-20T14:05:00+02:00", "voidReason": "Buyer changed their mind" } }"""))),
            @ApiResponse(responseCode = "400", description = "No reason",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "No reason",
                            value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "reason": "Please say why the sale is being voided." } }"""))),
            @ApiResponse(responseCode = "403", description = "Not an organiser",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Unknown, or another organiser's booking",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = """
                                    { "code": "404 NOT_FOUND", "message": "Booking not found", "data": null }"""))),
            @ApiResponse(responseCode = "409", description = "Not voidable",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "A ticket was scanned", value = EXAMPLE_TICKET_SCANNED),
                            @ExampleObject(name = "The event has started", value = EXAMPLE_EVENT_STARTED),
                            @ExampleObject(name = "Already voided", value = EXAMPLE_ALREADY_VOIDED),
                            @ExampleObject(name = "Not a cash sale", value = EXAMPLE_NOT_A_CASH_SALE)
                    })),
            @ApiResponse(responseCode = "503", description = "The event could not be checked just now",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Retry",
                            value = """
                                    { "code": "503 SERVICE_UNAVAILABLE", "message": "We couldn't check this event just now. Please try again.", "data": { "errorCode": "TRY_AGAIN" } }""")))
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> voidSale(
            @Parameter(description = "The sale's booking id") @PathVariable UUID bookingId,
            @Valid @RequestBody BoxOfficeVoidRequestDTO request, Authentication authentication) {
        BookingResponseDTO voided = boxOffice.voidSale(bookingId, request.getReason().strip(),
                AuthenticatedCaller.organizerUuid(authentication), AuthenticatedCaller.userUuid(authentication),
                authentication.getName());
        return ResponseEntity.ok(ApiResult.ok("Sale voided", voided));
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
