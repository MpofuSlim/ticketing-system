package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.report.BucketSize;
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
 * team-member endpoints. The api-gateway routes {@code /event-organizer/reports/**}
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
@PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
public class OrganizerReportController {

    private final OrganizerReportService reportService;

    @GetMapping("/revenue")
    @Operation(summary = "Revenue summary",
            description = "Period revenue for the calling organizer — gross (pre-refund), cash vs points, " +
                          "refunds, and net (= CONFIRMED total). Optional `eventId` narrows to one event; " +
                          "`from`/`to` default to the last 30 days.")
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
                                        "currency": "USD"
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
                reportService.revenueSummary(organizerUuid, eventId, from, to)));
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
                reportService.revenueByEvent(organizerUuid, from, to)));
    }

    @GetMapping("/by-category")
    @Operation(summary = "Revenue per ticket class",
            description = "Tickets sold and revenue per ticket class (category) for a single event. " +
                          "`eventId` is required; `from`/`to` default to the last 30 days.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Per-category breakdown",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "By category", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Per-category revenue retrieved",
                                      "data": [
                                        { "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11", "categoryName": "VIP", "ticketsSold": 120, "revenue": 12000.00 },
                                        { "categoryId": "1b2c3d4e-5f60-7182-93a4-b5c6d7e8f901", "categoryName": "General", "ticketsSold": 200, "revenue": 10000.00 }
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
                reportService.revenueByCategory(organizerUuid, eventId, from, to)));
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
                reportService.salesTimeSeries(organizerUuid, eventId, from, to, bucket)));
    }

    @GetMapping(value = "/bookings/export", produces = "text/csv")
    @Operation(summary = "Export confirmed bookings as CSV",
            description = "Streams the confirmed-bookings ledger for the window as CSV (one row per booking, " +
                          "ascending by createdAt) for the organizer's accountant. Phone numbers are masked. " +
                          "Returns Content-Disposition: attachment so browsers download. Optional `eventId`; " +
                          "`from`/`to` default to the last 30 days.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "CSV body (no ApiResult envelope)",
                    content = @Content(mediaType = "text/csv",
                            examples = @ExampleObject(name = "CSV", value = """
                                    confirmationNumber,eventId,createdAt,ticketsSold,totalAmount,cashAmount,pointsUsed,phone
                                    INN-20260502-AB12CD,3fa85f64-5717-4562-b3fc-2c963f66afa6,2026-05-02T15:45:00,2,200.00,200.00,0.00,+2637****6789
                                    INN-20260503-CD34EF,3fa85f64-5717-4562-b3fc-2c963f66afa6,2026-05-03T09:12:00,1,100.00,80.00,20.00,+2637****0000
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
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition", "attachment; filename=\"organizer-bookings.csv\"")
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
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()))) {
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
