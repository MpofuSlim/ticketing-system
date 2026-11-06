package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.BoxOfficeSaleRequestDTO;
import com.innbucks.bookingservice.dto.BoxOfficeVoidRequestDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.dto.report.AgentSalesReportDTO;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.idempotency.IdempotencyFilter;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import com.innbucks.bookingservice.service.BoxOfficeService;
import com.innbucks.bookingservice.service.SalesAgentReportService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The box office (V35, V36): tickets sold at a counter from the Foundry
 * portal or the InnBucks app, by the event's organizer or by a sales agent
 * on the organizer's event team. Rides the gateway's {@code /bookings/**}
 * route.
 *
 * <p>Who may sell is decided per sale, live, in {@link BoxOfficeService}: the
 * event's own organizer (an EVENT_ORGANIZER session whose
 * {@code organizerUuid} is the event's), or an account event-service lists
 * as a sales agent for the event (any of the three roles a sales agent's
 * session can carry: SALES_AGENT from the portal, CUSTOMER from the app, or
 * an organizer who sells for another organizer). Voids stay the organizer's
 * alone (owner decision, 2026-11-03, kept 2026-11-06).
 */
@Slf4j
@RestController
@RequestMapping("/bookings/box-office")
@Tag(name = "Box office", description = "Counter sales by the organiser or their sales agents, voids, and an "
        + "agent's own sales")
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
    static final String AGENT_SALE_EXAMPLE = """
            {
              "code": "201 CREATED",
              "message": "Cash sale recorded",
              "data": {
                "id": "2d4f6a8c-1b3d-4e5f-9a7b-6c8d0e2f4a6b",
                "customerName": "Tatenda Chirwa",
                "phoneNumber": "+263772000111",
                "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "confirmationNumber": "INN-20261120-9D04E1",
                "status": "CONFIRMED",
                "totalAmount": 150.00,
                "cashAmount": 150.00,
                "paymentMethod": "CASH",
                "paymentMethodLabel": "Cash (box office)",
                "soldBy": "Tendai Moyo",
                "items": [ { "categoryName": "VIP", "priceAtBooking": 150.00, "ticketNumber": "20261120-*****-**7KQ" } ]
              }
            }""";
    static final String ONLINE_SALE_EXAMPLE = """
            {
              "code": "201 CREATED",
              "message": "Sale created, awaiting payment",
              "data": {
                "id": "7e9a1c3b-5d7f-4a2b-8c4d-9e0f1a2b3c4d",
                "customerName": "Tatenda Chirwa",
                "phoneNumber": "+263772000111",
                "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "confirmationNumber": "INN-20261120-1A2B3C",
                "status": "PENDING",
                "totalAmount": 150.00,
                "soldBy": "Tendai Moyo",
                "expiresAt": "2026-11-20T10:07:00+02:00",
                "viewToken": "3f9c2a7e1b4d4e8fa0c6d2b9e5f71a34",
                "items": [ { "categoryName": "VIP", "priceAtBooking": 150.00, "ticketNumber": "20261120-*****-**M4P" } ]
              }
            }""";
    static final String EXAMPLE_KEY_REQUIRED = """
            { "code": "400 BAD_REQUEST", "message": "A box office sale needs an Idempotency-Key header, so a double click can't sell twice.", "data": { "errorCode": "IDEMPOTENCY_KEY_REQUIRED" } }""";
    static final String EXAMPLE_PROMO_CODE_NOT_ALLOWED = """
            { "code": "400 BAD_REQUEST", "message": "Promo codes can't be used on a sales agent's sale. Remove the code and try again.", "data": { "errorCode": "PROMO_CODE_NOT_ALLOWED" } }""";
    static final String EXAMPLE_NOT_A_SALES_AGENT = """
            { "code": "403 FORBIDDEN", "message": "You can't sell tickets for this event.", "data": { "errorCode": "NOT_A_SALES_AGENT" } }""";
    static final String EXAMPLE_AGENT_CAP_REACHED = """
            { "code": "409 CONFLICT", "message": "You may sell only 2 more tickets for this event (your limit is 200).", "data": { "errorCode": "AGENT_CAP_REACHED", "cap": 200, "remaining": 2 } }""";
    static final String EXAMPLE_SELLER_UNCHECKED = """
            { "code": "503 SERVICE_UNAVAILABLE", "message": "We couldn't check that you can sell for this event just now. Please try again.", "data": { "errorCode": "TRY_AGAIN" } }""";
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
    static final String MY_SALES_EXAMPLE = """
            {
              "code": "200 OK",
              "message": "Your sales",
              "data": {
                "from": null,
                "to": null,
                "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "entries": [
                  { "bookingId": "7e9a1c3b-5d7f-4a2b-8c4d-9e0f1a2b3c4d", "confirmationNumber": "INN-20261120-1A2B3C",
                    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "createdAt": "2026-11-20T10:02:00+02:00",
                    "customerName": "Tatenda Chirwa", "customerPhone": "****0111", "tickets": 1, "amount": 150.00,
                    "paymentMethod": "ECOCASH", "paymentMethodLabel": "EcoCash", "status": "CONFIRMED",
                    "commissionPercent": 5.00, "commission": 7.50 },
                  { "bookingId": "2d4f6a8c-1b3d-4e5f-9a7b-6c8d0e2f4a6b", "confirmationNumber": "INN-20261120-9D04E1",
                    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "createdAt": "2026-11-20T09:15:00+02:00",
                    "customerName": "Rufaro Moyo", "customerPhone": "****4567", "tickets": 2, "amount": 300.00,
                    "paymentMethod": "CASH", "paymentMethodLabel": "Cash (box office)", "status": "CONFIRMED",
                    "commissionPercent": 5.00, "commission": 15.00 },
                  { "bookingId": "5c6d7e8f-9a0b-4c1d-8e2f-3a4b5c6d7e8f", "confirmationNumber": "INN-20261120-77E0B2",
                    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "createdAt": "2026-11-20T08:40:00+02:00",
                    "customerName": "Farai Dube", "customerPhone": "****9902", "tickets": 1, "amount": 150.00,
                    "paymentMethod": "CASH", "paymentMethodLabel": "Cash (box office)", "status": "VOIDED",
                    "commissionPercent": 5.00, "commission": null }
                ],
                "totals": { "ticketsSold": 3, "cashTaken": 450.00, "cashVoided": 150.00, "onlineSales": 150.00,
                            "commissionEarned": 22.50, "handedOver": 200.00, "cashOwed": 100.00, "currency": "USD" },
                "handovers": [
                  { "id": "6d0f3c2b-8a41-4e7d-9b1c-2f5a7e9d0c11", "agentId": "4b1e2c3d-5f60-4a7b-8c9d-0e1f2a3b4c5d",
                    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "amount": 200.00, "currency": "USD",
                    "receivedAt": "2026-11-20T12:00:00+02:00", "note": "Midday cash",
                    "createdAt": "2026-11-20T12:01:30+02:00" }
                ]
              }
            }""";

    private final BoxOfficeService boxOffice;
    private final String deploymentCountry;
    private SalesAgentReportService salesAgentReports;

    public BoxOfficeController(BoxOfficeService boxOffice,
                               @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.boxOffice = boxOffice;
        this.deploymentCountry = deploymentCountry;
    }

    /** Setter, so the two-argument constructor stays; the agent's report needs it. */
    @Autowired(required = false)
    public void setSalesAgentReports(SalesAgentReportService salesAgentReports) {
        this.salesAgentReports = salesAgentReports;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SALES_AGENT','CUSTOMER')")
    @Operation(summary = "Sell tickets at the counter",
            description = "The event's organiser, or one of their sales agents, records a sale at the counter. "
                    + "The body is POST /bookings's, describing the BUYER (name, phone (required, the tickets go "
                    + "to it) and email), never the seller's session, plus `paymentMethod`. Every check an online "
                    + "sale gets applies, with the same refusals (SALES_NOT_STARTED, TERMS_NOT_ACCEPTED, "
                    + "ADD_ON_SOLD_OUT, sold out, ...).\n\n"
                    + "**CASH** (the default): CONFIRMED at once, paid in cash, with the seller recorded; the "
                    + "tickets go to the buyer exactly as for an online sale.\n\n"
                    + "**ONLINE**: the booking is held (PENDING, `expiresAt`) and the answer carries `viewToken`. "
                    + "The buyer then pays through POST /payments with the booking id (InnBucks code, EcoCash or "
                    + "card), and the client polls GET /bookings/view/{viewToken}. Unpaid, it expires like any "
                    + "checkout. The buyer's phone may hold at most 5 unpaid bookings in 15 minutes "
                    + "(TOO_MANY_UNPAID_BOOKINGS).\n\n"
                    + "**Who sells.** The event's organiser (promo codes allowed). Anyone else must be a sales agent "
                    + "the organiser assigned to the event, checked live on every sale: NOT_A_SALES_AGENT otherwise. "
                    + "A sales agent can't use a promo code (PROMO_CODE_NOT_ALLOWED) or sell past their ticket cap "
                    + "for the event (AGENT_CAP_REACHED, with `cap` and `remaining`), and earns their commission "
                    + "on what the buyer paid once the sale is CONFIRMED. Only the organiser voids.\n\n"
                    + "Requires an Idempotency-Key header: a retry with the same key gets the first answer, never a "
                    + "second sale.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sold (CASH), or held for payment (ONLINE)",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Organiser's cash sale", value = SALE_EXAMPLE),
                            @ExampleObject(name = "Sales agent's cash sale", value = AGENT_SALE_EXAMPLE),
                            @ExampleObject(name = "Online sale, awaiting payment", value = ONLINE_SALE_EXAMPLE)
                    })),
            @ApiResponse(responseCode = "400", description = "No Idempotency-Key, a missing buyer detail, a sales "
                    + "agent's promo code, or any refusal an online sale gives",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No Idempotency-Key", value = EXAMPLE_KEY_REQUIRED),
                            @ExampleObject(name = "Promo code on an agent's sale", value = EXAMPLE_PROMO_CODE_NOT_ALLOWED),
                            @ExampleObject(name = "No phone", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Please provide the buyer's phone number: the tickets are sent to it.", "data": null }"""),
                            @ExampleObject(name = "Unknown payment method", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "paymentMethod": "Must be one of: CASH, ONLINE." } }""")
                    })),
            @ApiResponse(responseCode = "403", description = "Neither the event's organiser nor one of their sales "
                    + "agents for it (an agent switched off, without Sell, or not assigned to the event included)",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Not a sales agent for this event", value = EXAMPLE_NOT_A_SALES_AGENT),
                            @ExampleObject(name = "No selling role", value = FORBIDDEN)
                    })),
            @ApiResponse(responseCode = "404", description = "Unknown or unpublished event (an organiser's session)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = """
                                    { "code": "404 NOT_FOUND", "message": "Event not found", "data": { "errorCode": "EVENT_NOT_FOUND" } }"""))),
            @ApiResponse(responseCode = "409", description = "Past the agent's ticket cap, any conflict an online sale "
                    + "gives (sold out, sales not started, event ended, an add-on sold out, terms changed), or the "
                    + "same Idempotency-Key still in progress",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Past the agent's ticket cap", value = EXAMPLE_AGENT_CAP_REACHED))),
            @ApiResponse(responseCode = "429", description = "ONLINE only: the buyer's phone has too many unpaid "
                    + "bookings",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Too many unpaid bookings for this phone", value = """
                                    { "code": "429 TOO_MANY_REQUESTS", "message": "This phone number has too many unpaid bookings. Pay for one of them, or try again later.", "data": { "errorCode": "TOO_MANY_UNPAID_BOOKINGS", "retryAfterSeconds": 540 } }"""))),
            @ApiResponse(responseCode = "503", description = "The event, or whether the caller may sell for it, could "
                    + "not be checked just now. Nothing was sold; send the same request again.",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Event not checked", value = """
                                    { "code": "503 SERVICE_UNAVAILABLE", "message": "We couldn't check this event just now. Please try again.", "data": { "errorCode": "TRY_AGAIN" } }"""),
                            @ExampleObject(name = "Seller not checked", value = EXAMPLE_SELLER_UNCHECKED)
                    }))
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> sell(
            @Parameter(description = "Any unique value per sale, e.g. a UUID made when the sale form opens")
            @RequestHeader(value = IdempotencyFilter.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody BoxOfficeSaleRequestDTO request, Authentication authentication) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "A box office sale needs an Idempotency-Key header, so a double click can't sell twice.");
        }
        // The BUYER's details come from the body only: the token is the seller's.
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
        log.info("POST /bookings/box-office eventId={} seats={} buyer={} method={}", request.getEventId(),
                request.getSeats().size(), MsisdnMasking.mask(phone), request.paymentMethodOrDefault());
        boolean online = request.paymentMethodOrDefault() == BoxOfficeSaleRequestDTO.PaymentMethod.ONLINE;
        BookingResponseDTO sold = boxOffice.sell(request, email, phone, caller(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(
                online ? "Sale created, awaiting payment" : "Cash sale recorded", sold));
    }

    @PostMapping("/{bookingId}/void")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Void a cash sale",
            description = "Cancels a box office cash sale, the organiser's own or one of their sales agents': its "
                    + "tickets stop working at the gate, and its seats, add-ons and promo-code use go back on sale "
                    + "(an agent's sale also stops counting against the agent's cap). Its assigned numbers are "
                    + "never given again. Only the event's organiser, only a cash sale, only before the event "
                    + "starts and before any of its tickets is scanned. The buyer is told the tickets are "
                    + "cancelled and to see the box office about their money. Shown in the cash-up report with "
                    + "who voided it and why, on the till of whoever sold it.")
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

    @GetMapping("/my-sales")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SALES_AGENT','CUSTOMER')")
    @Operation(summary = "My sales as a sales agent",
            description = "Every sale the signed-in account made as a sales agent, newest first, made or voided in "
                    + "the window, with what they add up to and the cash the organisers recorded receiving from you. "
                    + "Only your own sales, by the account you are signed in with: the same whether you sell from "
                    + "the InnBucks app or the Foundry portal. The buyer's phone is shown as its last four digits. "
                    + "`status`: CONFIRMED (sold), PENDING (an online sale awaiting payment), VOIDED (a cash sale "
                    + "the organiser voided) or CANCELLED (an online sale nobody paid for). `cashOwed` = cash taken "
                    + "- cash voided - handed over. Optional `eventId`; `from`/`to` (ISO dates, UTC days) narrow "
                    + "the window, and with neither the report covers every sale, so `cashOwed` is everything "
                    + "still to hand over. A handover recorded against an event counts only when that event is "
                    + "the filter or there is none.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Your sales",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Your sales", value = MY_SALES_EXAMPLE))),
            @ApiResponse(responseCode = "400", description = "Invalid range, or a session with no account",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Range",
                            value = """
                                    { "code": "400 BAD_REQUEST", "message": "'from' (2026-06-01) must not be after 'to' (2026-05-31).", "data": null }"""))),
            @ApiResponse(responseCode = "403", description = "No selling role",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN)))
    })
    public ResponseEntity<ApiResult<AgentSalesReportDTO>> mySales(
            Authentication authentication,
            @Parameter(description = "Only this event's sales") @RequestParam(required = false) UUID eventId,
            @Parameter(description = "First day, ISO date (UTC day)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day, ISO date (UTC day)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID me = AuthenticatedCaller.userUuid(authentication);
        if (me == null) {
            throw new BadRequestException("Your session is missing account information. Please sign in again.");
        }
        if (salesAgentReports == null) {
            throw new IllegalStateException("SalesAgentReportService is not wired");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.ok("Your sales", salesAgentReports.mySales(me, eventId, from, to)));
    }

    private static BoxOfficeService.Caller caller(Authentication authentication) {
        boolean organizerRole = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_EVENT_ORGANIZER".equals(a.getAuthority()));
        return new BoxOfficeService.Caller(AuthenticatedCaller.userUuid(authentication),
                AuthenticatedCaller.organizerUuid(authentication), organizerRole,
                // A session with no email (the InnBucks app) has the name "".
                authentication == null ? null : trimToNull(authentication.getName()));
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
