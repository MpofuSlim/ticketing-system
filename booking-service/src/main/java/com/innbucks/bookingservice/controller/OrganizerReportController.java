package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.report.BucketSize;
import com.innbucks.bookingservice.dto.report.ReportMetaDTO;
import com.innbucks.bookingservice.dto.report.CategoryRevenueDTO;
import com.innbucks.bookingservice.dto.report.EventRevenueDTO;
import com.innbucks.bookingservice.dto.report.RevenueSummaryDTO;
import com.innbucks.bookingservice.dto.report.SalesBucketDTO;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import com.innbucks.bookingservice.service.OrganizerReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Financial reports for an EVENT_ORGANIZER over the events they own.
 *
 * <p>Every endpoint is scoped to the caller's {@code organizerUuid} JWT claim
 * (an organizer can only read their own events' figures) and accepts an
 * optional {@code from}/{@code to} date window (ISO dates; defaults to the last
 * 30 days) plus, where relevant, an optional {@code eventId} filter — one
 * filterable endpoint per report rather than a fan of fixed-period variants.
 *
 * <p>Routing note: this lives in booking-service (it owns the sales data), but
 * shares the {@code /event-organizer/**} prefix with user-service's
 * organizer endpoints. The api-gateway routes {@code /event-organizer/reports/**}
 * to booking-service ahead of the catch-all {@code /event-organizer/**} ->
 * user-service route.
 */
@RestController
@RequestMapping("/event-organizer/reports")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Organizer Reports",
     description = "Financial reports for an EVENT_ORGANIZER (scoped to their own events) or a " +
                   "SUPER_ADMIN (fleet-wide). Revenue is recognised from CONFIRMED bookings (paid); " +
                   "admin reversals show as refunds.")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_OFFICER','PRODUCT_MANAGER')")
public class OrganizerReportController {

    private final OrganizerReportService reportService;

    /** Booking questions (V32); a setter so the one-service constructor tests use stays. */
    private com.innbucks.bookingservice.service.BookingQuestionReportService bookingQuestionReportService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBookingQuestionReportService(
            com.innbucks.bookingservice.service.BookingQuestionReportService bookingQuestionReportService) {
        this.bookingQuestionReportService = bookingQuestionReportService;
    }

    @GetMapping("/cash-up")
    @Operation(summary = "Box office cash-up",
            description = "Cash taken and handed back at the box office (V35), per organiser account, with every "
                    + "sale and void behind it, oldest first. A sale counts on the day it was made, a void on the "
                    + "day it was made: a sale voided the next day is in both days' cash-ups, each on its side. "
                    + "`net` is what the account's till should hold. For one day, send `from` and `to` the same "
                    + "date; days are UTC days, like every organiser report. Optional `eventId`; `from`/`to` "
                    + "default to the last 30 days.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cash-up",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Cash-up retrieved",
                              "data": {
                                "sellers": [
                                  { "seller": "organizer@example.com", "sales": 2, "tickets": 3, "taken": 450.00,
                                    "voids": 1, "returned": 150.00, "net": 300.00 }
                                ],
                                "totals": { "sales": 2, "tickets": 3, "taken": 450.00, "voids": 1, "returned": 150.00, "net": 300.00 },
                                "entries": [
                                  { "type": "SALE", "at": "2026-11-20T10:02:00+02:00", "by": "organizer@example.com",
                                    "bookingId": "9b2e4c1a-7d3f-4e5a-8b6c-1a2b3c4d5e6f", "confirmationNumber": "INN-20261120-4F2A9C",
                                    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "customerName": "Rufaro Moyo",
                                    "tickets": 2, "amount": 300.00, "reason": null },
                                  { "type": "SALE", "at": "2026-11-20T11:40:00+02:00", "by": "organizer@example.com",
                                    "bookingId": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f", "confirmationNumber": "INN-20261120-7B1D03",
                                    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "customerName": "Tendai Ncube",
                                    "tickets": 1, "amount": 150.00, "reason": null },
                                  { "type": "VOID", "at": "2026-11-20T11:52:00+02:00", "by": "organizer@example.com",
                                    "bookingId": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f", "confirmationNumber": "INN-20261120-7B1D03",
                                    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "customerName": "Tendai Ncube",
                                    "tickets": 1, "amount": 150.00, "reason": "Buyer changed their mind" }
                                ]
                              },
                              "meta": { "from": "2026-11-20", "to": "2026-11-20", "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "organizerUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e", "platformWide": false }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid range",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "'from' (2026-06-01) must not be after 'to' (2026-05-31).", "data": null }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Not an organizer or platform staff",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                            """)))
    })
    public ResponseEntity<ApiResult<com.innbucks.bookingservice.dto.report.CashUpReportDTO>> cashUp(
            Authentication authentication,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID organizerUuid = resolveScope(authentication);
        return ResponseEntity.ok(ApiResult.ok("Cash-up retrieved",
                reportService.cashUp(organizerUuid, eventId, from, to),
                reportService.resolvedMeta(organizerUuid, eventId, from, to)));
    }

    @GetMapping("/add-ons")
    @Operation(summary = "Add-on sales",
            description = "Add-ons sold on CONFIRMED bookings in the window (V33), one row per add-on, highest "
                    + "revenue first; `name` is the latest it sold under. Optional `eventId`; `from`/`to` default "
                    + "to the last 30 days. Refunded bookings are not counted. The revenue summary includes add-on "
                    + "revenue (it sums booking totals); `/by-category` counts ticket prices only, so the two "
                    + "together make up the summary's net.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Add-on sales",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Add-on sales retrieved",
                              "data": [
                                { "addOnId": "a1b2c3d4-0e1f-4a2b-8c3d-4e5f6a7b8c02", "name": "Meal voucher",
                                  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "categoryName": "VIP", "sold": 30, "revenue": 300.00 },
                                { "addOnId": "a1b2c3d4-0e1f-4a2b-8c3d-4e5f6a7b8c01", "name": "Parking pass",
                                  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "categoryName": "VIP", "sold": 12, "revenue": 60.00 }
                              ],
                              "meta": { "from": "2026-10-05", "to": "2026-11-03", "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "organizerUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e", "platformWide": false }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid range",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "'from' (2026-06-01) must not be after 'to' (2026-05-31).", "data": null }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Not an organizer or platform staff",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                            """)))
    })
    public ResponseEntity<ApiResult<List<com.innbucks.bookingservice.dto.report.AddOnSalesDTO>>> addOns(
            Authentication authentication,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID organizerUuid = resolveScope(authentication);
        return ResponseEntity.ok(ApiResult.ok("Add-on sales retrieved",
                reportService.addOnSales(organizerUuid, eventId, from, to),
                reportService.resolvedMeta(organizerUuid, eventId, from, to)));
    }

    @GetMapping("/booking-questions")
    @Operation(summary = "How an event's tickets answered its booking questions",
            description = "Counts over the event's CONFIRMED tickets (V32): per question, how many answered it; "
                    + "CHOICE gives each option's count (most picked first), CHECKBOX gives yes/no; TEXT, NUMBER "
                    + "and DATE give the count only (read the values in the guest list). `termsAccepted` counts "
                    + "confirmed bookings per accepted terms version. Answers are deleted 90 days after the event "
                    + "ends: `answersDeleted` is then true and `questions` []. `eventId` is required. An "
                    + "organizer reads only their own event's bookings; staff read any.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Report",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Booking question report retrieved",
                              "data": {
                                "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                "confirmedTickets": 120,
                                "answersDeleted": false,
                                "questions": [
                                  { "questionId": "7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e01", "label": "T-shirt size", "type": "CHOICE",
                                    "answered": 118, "options": [ { "value": "M", "count": 61 }, { "value": "L", "count": 30 },
                                    { "value": "S", "count": 20 }, { "value": "XL", "count": 7 } ], "yes": null, "no": null },
                                  { "questionId": "7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e02", "label": "Emergency contact (name and phone)",
                                    "type": "TEXT", "answered": 120, "options": [], "yes": null, "no": null },
                                  { "questionId": "7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e04", "label": "Send me news about future races",
                                    "type": "CHECKBOX", "answered": 120, "options": [], "yes": 45, "no": 75 }
                                ],
                                "termsAccepted": [ { "version": 2, "bookings": 80 }, { "version": 1, "bookings": 10 } ]
                              },
                              "meta": { "from": null, "to": null, "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "organizerUuid": null, "platformWide": true }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "eventId missing",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "eventId": "Required." } }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Not an organizer or platform staff",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                            """)))
    })
    public ResponseEntity<ApiResult<?>> bookingQuestions(
            Authentication authentication,
            @RequestParam(required = false) UUID eventId) {
        if (eventId == null) {
            return ResponseEntity.badRequest().body(ApiResult.<java.util.Map<String, String>>builder()
                    .code("400 BAD_REQUEST")
                    .message("Validation failed")
                    .data(java.util.Map.of("eventId", "Required."))
                    .build());
        }
        UUID organizerUuid = resolveScope(authentication);
        return ResponseEntity.ok(ApiResult.ok("Booking question report retrieved",
                bookingQuestionReportService.report(organizerUuid, eventId),
                com.innbucks.bookingservice.dto.report.ReportMetaDTO.of(null, null, eventId, organizerUuid)));
    }

    @GetMapping("/revenue")
    @Operation(summary = "Revenue summary",
            description = "Period revenue for the calling organizer — gross (pre-refund), cash vs points, " +
                          "refunds, and net (= CONFIRMED total). `byPaymentMethod` splits net by how each booking was " +
                          "paid (InnBucks, EcoCash, bank card; NOT_RECORDED when none is on record); its rows add " +
                          "up to `netRevenue`, `confirmedBookings` and `ticketsSold`. Optional `eventId` narrows to " +
                          "one event; `from`/`to` default to the last 30 days, so for a whole event pass a `from` " +
                          "on or before its first sale.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Summary computed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Revenue summary", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Revenue summary retrieved",
                                      "data": {
                                        "from": "2026-05-01",
                                        "to": "2026-05-31",
                                        "eventId": null,
                                        "confirmedBookings": 128,
                                        "ticketsSold": 342,
                                        "grossRevenue": 34200.00,
                                        "cashCollected": 33950.00,
                                        "pointsRedeemed": 2500.00,
                                        "refundedBookings": 3,
                                        "refundedAmount": 300.00,
                                        "netRevenue": 33900.00,
                                        "averageOrderValue": 264.84,
                                        "currency": "USD",
                                        "byPaymentMethod": [
                                          { "method": "INNBUCKS", "label": "InnBucks", "bookings": 101, "ticketsSold": 270, "amount": 26700.00 },
                                          { "method": "ECOCASH", "label": "EcoCash", "bookings": 21, "ticketsSold": 56, "amount": 5600.00 },
                                          { "method": "CARD", "label": "Bank card (ZimSwitch)", "bookings": 6, "ticketsSold": 16, "amount": 1600.00 }
                                        ]
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Invalid range (from after to) or missing organizer identity",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "'from' (2026-06-01) must not be after 'to' (2026-05-31).",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller holds neither EVENT_ORGANIZER nor SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<RevenueSummaryDTO>> revenue(
            Authentication authentication,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID organizerUuid = resolveScope(authentication);
        log.debug("GET /event-organizer/reports/revenue organizer={} eventId={} from={} to={}",
                organizerUuid, eventId, from, to);
        return ResponseEntity.ok(ApiResult.ok("Revenue summary retrieved",
                reportService.revenueSummary(organizerUuid, eventId, from, to),
                reportService.resolvedMeta(organizerUuid, eventId, from, to)));
    }

    @GetMapping("/by-event")
    @Operation(summary = "Revenue per event",
            description = "One row per event the organizer owns that had a sale or refund in the window, " +
                          "ordered by net revenue. `from`/`to` default to the last 30 days.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Per-event breakdown",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "By event", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Per-event revenue retrieved",
                                      "data": [
                                        {
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "confirmedBookings": 120,
                                          "ticketsSold": 320,
                                          "grossRevenue": 32000.00,
                                          "refundedAmount": 200.00,
                                          "netRevenue": 31800.00
                                        },
                                        {
                                          "eventId": "9c1f0b2a-3d4e-5f60-7182-93a4b5c6d7e8",
                                          "confirmedBookings": 8,
                                          "ticketsSold": 22,
                                          "grossRevenue": 2200.00,
                                          "refundedAmount": 100.00,
                                          "netRevenue": 2100.00
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid range"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not an EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<List<EventRevenueDTO>>> byEvent(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID organizerUuid = resolveScope(authentication);
        log.debug("GET /event-organizer/reports/by-event organizer={} from={} to={}", organizerUuid, from, to);
        return ResponseEntity.ok(ApiResult.ok("Per-event revenue retrieved",
                reportService.revenueByEvent(organizerUuid, from, to),
                reportService.resolvedMeta(organizerUuid, null, from, to)));
    }

    @GetMapping("/by-category")
    @Operation(summary = "Revenue per ticket class",
            description = "Tickets sold and revenue per ticket class (category) for a single event, each split "
                          + "by `priceTiers`: the price tier every ticket was sold at (e.g. Early bird), in the "
                          + "order the tiers first sold, with the \"Full price\" row last. `eventId` is required; "
                          + "`from`/`to` default to the last 30 days.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Per-category breakdown",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "By category", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Per-category revenue retrieved",
                                      "data": [
                                        { "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11", "categoryName": "VIP",
                                          "ticketsSold": 120, "revenue": 13100.00,
                                          "priceTiers": [
                                            { "priceTierId": "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", "name": "Early bird", "ticketsSold": 100, "revenue": 10000.00 },
                                            { "priceTierId": "7d2f3e4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a", "name": "Phase 1", "ticketsSold": 15, "revenue": 1800.00 },
                                            { "priceTierId": null, "name": "Full price", "ticketsSold": 5, "revenue": 750.00 }
                                          ] },
                                        { "categoryId": "1b2c3d4e-5f60-7182-93a4-b5c6d7e8f901", "categoryName": "General",
                                          "ticketsSold": 200, "revenue": 10000.00,
                                          "priceTiers": [
                                            { "priceTierId": null, "name": "Full price", "ticketsSold": 200, "revenue": 10000.00 }
                                          ] }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "eventId missing or invalid range",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "400 BAD_REQUEST", "message": "eventId is required for the per-category breakdown.", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not an EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<List<CategoryRevenueDTO>>> byCategory(
            Authentication authentication,
            @RequestParam UUID eventId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID organizerUuid = resolveScope(authentication);
        log.debug("GET /event-organizer/reports/by-category organizer={} eventId={} from={} to={}",
                organizerUuid, eventId, from, to);
        return ResponseEntity.ok(ApiResult.ok("Per-category revenue retrieved",
                reportService.revenueByCategory(organizerUuid, eventId, from, to),
                reportService.resolvedMeta(organizerUuid, eventId, from, to)));
    }

    @GetMapping("/time-series")
    @Operation(summary = "Sales time-series",
            description = "Net revenue and tickets bucketed by day or week for a trend chart. Optional " +
                          "`eventId`; `bucket` is DAY (default) or WEEK; `from`/`to` default to the last 30 days. " +
                          "Buckets with no sales are omitted.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Time-series computed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Daily series", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Sales time-series retrieved",
                                      "data": [
                                        { "bucketStart": "2026-05-04", "confirmedBookings": 18, "ticketsSold": 47, "revenue": 4700.00 },
                                        { "bucketStart": "2026-05-05", "confirmedBookings": 22, "ticketsSold": 61, "revenue": 6100.00 }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid range"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not an EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<List<SalesBucketDTO>>> timeSeries(
            Authentication authentication,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false, defaultValue = "DAY") BucketSize bucket,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID organizerUuid = resolveScope(authentication);
        log.debug("GET /event-organizer/reports/time-series organizer={} eventId={} bucket={} from={} to={}",
                organizerUuid, eventId, bucket, from, to);
        return ResponseEntity.ok(ApiResult.ok("Sales time-series retrieved",
                reportService.salesTimeSeries(organizerUuid, eventId, from, to, bucket),
                reportService.resolvedMeta(organizerUuid, eventId, from, to)));
    }

    @GetMapping(value = "/bookings/export", produces = "text/csv")
    @Operation(summary = "Export confirmed bookings as CSV",
            description = "Streams the confirmed-bookings ledger for the window as CSV (one row per booking, " +
                          "ascending by createdAt) for the organizer's accountant. Phone numbers are masked. " +
                          "Returns Content-Disposition: attachment so browsers download. Optional `eventId`; " +
                          "`from`/`to` default to the last 30 days.\n\n" +
                          "V22 appends two columns: `customerName` (the purchaser) and `attendees` (named guests " +
                          "on the booking's tickets, `; `-joined, names only — for contacts use GET /bookings/by-event/{id}). " +
                          "V27 appends `promoCode` and `discountAmount` (empty when no code was used); `totalAmount` " +
                          "is already after the discount. V29 appends `paymentMethod`: \"InnBucks\", \"EcoCash\" or " +
                          "\"Bank card (ZimSwitch)\", empty when not recorded.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "CSV body (no ApiResult envelope)",
                    content = @Content(mediaType = "text/csv",
                            examples = @ExampleObject(name = "CSV", value = """
                                    confirmationNumber,eventId,createdAt,ticketsSold,totalAmount,cashAmount,pointsUsed,phone,customerName,attendees,promoCode,discountAmount,paymentMethod
                                    INN-20260502-AB12CD,3fa85f64-5717-4562-b3fc-2c963f66afa6,2026-05-02T15:45:00,2,180.00,180.00,0.00,+2637****6789,Alice Moyo,Tendai Ncube,EARLY10,20.00,EcoCash
                                    INN-20260503-CD34EF,3fa85f64-5717-4562-b3fc-2c963f66afa6,2026-05-03T09:12:00,1,100.00,80.00,20.00,+2637****0000,Carol Dube,,,,InnBucks
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid range"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not an EVENT_ORGANIZER")
    })
    public ResponseEntity<String> exportCsv(
            Authentication authentication,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID organizerUuid = resolveScope(authentication);
        log.info("CSV export /event-organizer/reports/bookings/export organizer={} eventId={} from={} to={}",
                organizerUuid, eventId, from, to);
        String csv = reportService.confirmedBookingsCsv(organizerUuid, eventId, from, to);
        // Provenance for the export. A CSV has nowhere to put the JSON `meta`
        // the other report endpoints now carry, and prepending a preamble row
        // would break every naive parser that reads line 1 as the header — so
        // the resolved period rides on the FILENAME instead, which is what
        // actually survives into someone's Downloads folder. Same resolvedMeta
        // as the JSON reports, so the dates cannot disagree with the rows.
        ReportMetaDTO meta = reportService.resolvedMeta(organizerUuid, eventId, from, to);
        String filename = "organizer-bookings_" + meta.from() + "_to_" + meta.to() + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    /**
     * Resolve the report scope from the caller's token. SUPER_ADMIN tokens
     * carry no organizer claim and get {@code null} = fleet-wide (the
     * repository treats a null organizer as "all organizers"). Every current
     * EVENT_ORGANIZER token carries the claim; a legacy token minted before
     * the claim existed would not, so fail with a clear 400 telling them to
     * re-login rather than silently returning another organizer's (or
     * empty) data.
     */
    @org.springframework.lang.Nullable
    private UUID resolveScope(Authentication authentication) {
        if (AuthenticatedCaller.isPlatformStaff(authentication)) {
            return null;
        }
        UUID organizerUuid = AuthenticatedCaller.organizerUuid(authentication);
        if (organizerUuid == null) {
            throw new BadRequestException(
                    "Your session is missing organizer identity. Please sign out and log in again.");
        }
        return organizerUuid;
    }
}
