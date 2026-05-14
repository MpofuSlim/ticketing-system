package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.EventAnalyticsDTO;
import com.innbucks.seatservice.service.SeatCategoryAnalyticsService;
import com.innbucks.seatservice.service.SeatCategoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/seat-categories")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Seat Categories", description = "Manage seat categories for events.")
public class SeatCategoryController {

    private final SeatCategoryService categoryService;
    private final SeatCategoryAnalyticsService analyticsService;

    @GetMapping
    @SecurityRequirements()
    @Operation(summary = "List categories by event", description = "Returns all active seat categories for a given event.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Categories returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CreateCategoryResponseDTO.class),
                            examples = @ExampleObject(name = "Categories by event", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Categories retrieved successfully",
                                      "data": [
                                        {
                                          "seatCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "name": "VIP",
                                          "description": "Front rows",
                                          "price": 100.00,
                                          "sections": [
                                            { "section": "A", "seatCount": 25 },
                                            { "section": "B", "seatCount": 25 }
                                          ]
                                        },
                                        {
                                          "seatCategoryId": "9e2c5b4f-2d1f-4a28-8b1c-2e5c8a7b6d22",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "name": "GA",
                                          "description": "General admission",
                                          "price": 60.00,
                                          "sections": [
                                            { "section": "F", "seatCount": 50 }
                                          ]
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<List<CreateCategoryResponseDTO>>> getByEvent(
            @Parameter(description = "Event UUID") @RequestParam UUID eventId
    ) {
        log.debug("GET /seat-categories eventId={}", eventId);
        List<CreateCategoryResponseDTO> result = categoryService.getCategoriesByEvent(eventId);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.error(HttpStatus.NOT_FOUND, "Not found"));
        }
        return ResponseEntity.ok(ApiResult.ok("Categories retrieved successfully", result));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Create category", description = "Creates a seat category for an event. Requires EVENT_ORGANIZER role.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Category created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CreateCategoryResponseDTO.class),
                            examples = @ExampleObject(name = "Category created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Seat category created successfully",
                                      "data": {
                                        "seatCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "VIP",
                                        "description": "Front rows",
                                        "price": 100.00,
                                        "sections": [
                                          { "section": "A", "seatCount": 25 },
                                          { "section": "B", "seatCount": 25 }
                                        ]
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation or domain error")
    })
    public ResponseEntity<ApiResult<CreateCategoryResponseDTO>> createCategory(
            @Valid @RequestBody CreateCategoryRequestDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        String requesterEmail = authentication.getName();
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        String authHeader = httpRequest.getHeader("Authorization");
        log.info("POST /seat-categories eventId={} name={} requesterEmail={} isAdmin={}",
                request.getEventId(), request.getName(), requesterEmail, isAdmin);
        CreateCategoryResponseDTO created = categoryService.createCategory(
                request, requesterEmail, isAdmin, authHeader);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Seat category created successfully", created));
    }

    @GetMapping("/analytics")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Get event analytics",
            description = "Returns aggregated analytics for every seat category in an event: " +
                    "category metadata, per-status seat counts, paginated bookings + per-category revenue, " +
                    "and event-level rollup totals across all categories. " +
                    "Restricted to EVENT_ORGANIZER because the response includes customer emails. " +
                    "Tolerates booking-service downtime — `bookingServiceReachable=false` indicates " +
                    "the bookings sections reflect no-data, not zero-bookings. " +
                    "Pagination applies per-category: each category's `bookings.items` is sliced to " +
                    "the requested page; the aggregate counts/revenue stay computed across the full set."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Analytics returned (empty categories list if event has none)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventAnalyticsDTO.class),
                            examples = @ExampleObject(name = "Event analytics", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event analytics retrieved successfully",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "categoryCount": 2,
                                        "totals": {
                                          "totalSeats": 100,
                                          "availableSeats": 40,
                                          "lockedSeats": 5,
                                          "bookedSeats": 55,
                                          "totalBookings": 53,
                                          "pendingBookings": 3,
                                          "paidBookings": 47,
                                          "cancelledBookings": 3,
                                          "pendingRevenue": 300.00,
                                          "paidRevenue": 4700.00,
                                          "potentialRevenue": 8000.00,
                                          "mostRecentBookingAt": "2026-05-02T15:45:00"
                                        },
                                        "categories": [
                                          {
                                            "category": {
                                              "id": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                              "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                              "name": "VIP",
                                              "description": "Front rows",
                                              "price": 100.00,
                                              "totalSeats": 50,
                                              "cachedAvailableSeats": 12,
                                              "createdAt": "2026-04-25T08:00:00",
                                              "updatedAt": "2026-05-02T15:45:00"
                                            },
                                            "seatStatusCounts": { "total": 50, "available": 12, "locked": 3, "booked": 35 },
                                            "bookings": {
                                              "totalRecords": 38,
                                              "pendingRecords": 2,
                                              "paidRecords": 33,
                                              "cancelledRecords": 3,
                                              "pendingRevenue": 200.00,
                                              "paidRevenue": 3300.00,
                                              "potentialRevenue": 5000.00,
                                              "mostRecentBookingAt": "2026-05-02T15:45:00",
                                              "pageNumber": 0,
                                              "pageSize": 20,
                                              "totalPages": 2,
                                              "items": [
                                                {
                                                  "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                  "userEmail": "alice@example.com",
                                                  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                                  "status": "CONFIRMED",
                                                  "confirmationNumber": "INN-20260502-AB12CD",
                                                  "seatId": "11111111-2222-3333-4444-555555555555",
                                                  "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                                  "categoryName": "VIP",
                                                  "rowLabel": "A",
                                                  "seatNumber": 12,
                                                  "ticketNumber": "20260502-12345A",
                                                  "priceAtBooking": 100.00,
                                                  "bookedAt": "2026-05-02T15:45:00",
                                                  "updatedAt": "2026-05-02T15:45:00",
                                                  "expiresAt": null
                                                },
                                                {
                                                  "bookingId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                                  "userEmail": "bob@example.com",
                                                  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                                  "status": "PENDING",
                                                  "confirmationNumber": "INN-20260502-EF34GH",
                                                  "seatId": "22222222-3333-4444-5555-666666666666",
                                                  "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                                  "categoryName": "VIP",
                                                  "rowLabel": "A",
                                                  "seatNumber": 13,
                                                  "ticketNumber": "20260502-67890B",
                                                  "priceAtBooking": 100.00,
                                                  "bookedAt": "2026-05-02T15:43:30",
                                                  "updatedAt": "2026-05-02T15:43:30",
                                                  "expiresAt": "2026-05-02T15:48:30"
                                                }
                                              ]
                                            }
                                          },
                                          {
                                            "category": {
                                              "id": "9e2c5b4f-2d1f-4a28-8b1c-2e5c8a7b6d22",
                                              "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                              "name": "GA",
                                              "description": "General admission",
                                              "price": 60.00,
                                              "totalSeats": 50,
                                              "cachedAvailableSeats": 28,
                                              "createdAt": "2026-04-25T08:00:00",
                                              "updatedAt": "2026-05-02T14:00:00"
                                            },
                                            "seatStatusCounts": { "total": 50, "available": 28, "locked": 2, "booked": 20 },
                                            "bookings": {
                                              "totalRecords": 15,
                                              "pendingRecords": 1,
                                              "paidRecords": 14,
                                              "cancelledRecords": 0,
                                              "pendingRevenue": 100.00,
                                              "paidRevenue": 1400.00,
                                              "potentialRevenue": 3000.00,
                                              "mostRecentBookingAt": "2026-05-02T14:00:00",
                                              "pageNumber": 0,
                                              "pageSize": 20,
                                              "totalPages": 1,
                                              "items": []
                                            }
                                          }
                                        ],
                                        "bookingServiceReachable": true,
                                        "fetchedAt": "2026-05-02T15:46:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<EventAnalyticsDTO>> getEventAnalytics(
            @Parameter(description = "Event UUID") @RequestParam UUID eventId,
            @Parameter(description = "Zero-based page index for the per-category bookings list (default 0)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size for the per-category bookings list (default 20, max 100)")
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        String requesterEmail = authentication.getName();
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.info("GET /seat-categories/analytics eventId={} requesterEmail={} isAdmin={} page={} size={}",
                eventId, requesterEmail, isAdmin, page, size);
        // Forward the inbound Authorization header so booking-service's
        // matching role check sees the same caller.
        String authHeader = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
        EventAnalyticsDTO analytics = analyticsService.getEventAnalytics(
                eventId, requesterEmail, isAdmin, page, size, authHeader);
        return ResponseEntity.ok(ApiResult.ok("Event analytics retrieved successfully", analytics));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Delete category", description = "Soft-deletes a seat category. Requires EVENT_ORGANIZER role.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Category deleted",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Category deleted", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Seat category deleted successfully",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Category not found")
    })
    public ResponseEntity<ApiResult<Void>> deleteCategory(
            @PathVariable UUID id,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String requesterEmail = authentication.getName();
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        String authHeader = httpRequest.getHeader("Authorization");
        log.info("DELETE /seat-categories/{} requesterEmail={} isAdmin={}", id, requesterEmail, isAdmin);
        categoryService.deleteCategory(id, requesterEmail, isAdmin, authHeader);
        return ResponseEntity.ok(ApiResult.ok("Seat category deleted successfully", null));
    }

    private static boolean hasRole(Authentication authentication, String role) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }
}
