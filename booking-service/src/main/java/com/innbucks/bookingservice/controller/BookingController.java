package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CategoryBookingDTO;
import com.innbucks.bookingservice.dto.ConfirmBookingRequestDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.dto.EventActiveCountDTO;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.security.MinTier;
import com.innbucks.bookingservice.security.TierAccessInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import com.innbucks.bookingservice.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Bookings", description = "Create, query, confirm, and cancel bookings.")
public class BookingController {

    private final BookingService bookingService;
    private final com.innbucks.bookingservice.client.UserServiceClient userServiceClient;

    @PostMapping
    @Operation(summary = "Create booking", description = "Creates a new pending booking. The customer's tier is resolved from user-service via the phone number — JWT phone wins when present, otherwise the request body's `phoneNumber`. " +
            "Returns 404 if the phone number is not registered in user-service. Per-tier seat-count limits in BookingService still apply.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Booking created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Booking created successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "PENDING",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T15:45:00",
                                        "expiresAt": "2026-05-02T15:50:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Customer tier below minimum. Envelope: { code: '422', message: <reason>, data: { requiredTier, currentTier } }"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation or domain error")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> createBooking(
            @Valid @RequestBody CreateBookingRequestDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());

        String phoneNumber = authenticated ? extractPhoneNumber(authentication) : null;
        if (phoneNumber == null || phoneNumber.isBlank()) {
            phoneNumber = request.getPhoneNumber();
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "phoneNumber is required");
        }

        // Look up the customer's current tier in user-service. If the phone
        // isn't registered there, refuse the booking with a 404 — guests
        // must register (at least tier 1) before booking online.
        com.innbucks.bookingservice.dto.CustomerTierResponseDTO tierData;
        try {
            com.innbucks.bookingservice.dto.ApiResult<com.innbucks.bookingservice.dto.CustomerTierResponseDTO> result =
                    userServiceClient.getCustomerTier(phoneNumber);
            tierData = result == null ? null : result.getData();
        } catch (Exception ex) {
            log.warn("user-service tier lookup failed phoneNumber={} cause={}", phoneNumber, ex.toString());
            tierData = null;
        }
        if (tierData == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Phone number " + phoneNumber + " is not registered. Please register before booking.");
        }
        int tier = tierData.getCurrentTier();

        String userEmail = authenticated ? authentication.getName() : request.getUserEmail();
        if (userEmail == null || userEmail.isBlank()) {
            userEmail = tierData.getEmail();
        }
        if (userEmail != null && userEmail.isBlank()) {
            userEmail = null;
        }
        log.info("POST /bookings userEmail={} tier={} phoneNumber={} eventId={} seats={}",
                userEmail, tier, phoneNumber, request.getEventId(), request.getSeats().size());
        BookingResponseDTO created = bookingService.createBooking(userEmail, tier, phoneNumber, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Booking created successfully", created));
    }

    private String extractPhoneNumber(Authentication authentication) {
        Object details = authentication.getDetails();
        return details instanceof JwtAuthDetails d ? d.phoneNumber() : null;
    }

    // Tier is stamped on the request by TierAccessInterceptor after a live
    // user-service lookup, so the JWT's (potentially stale) tier claim never
    // feeds the per-tier seat-count check.
    private int currentTier(HttpServletRequest request) {
        Object value = request.getAttribute(TierAccessInterceptor.CURRENT_TIER_ATTRIBUTE);
        return value instanceof Integer i ? i : 0;
    }

    @GetMapping("/my")
    @Operation(summary = "List my bookings", description = "Returns all bookings for the authenticated user.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Bookings returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "My bookings", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Bookings retrieved successfully",
                                      "data": [
                                        {
                                          "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                          "userEmail": "alice@example.com",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "confirmationNumber": "INN-20260502-AB12CD",
                                          "status": "CONFIRMED",
                                          "totalAmount": 200.00,
                                          "items": [
                                            {
                                              "seatId": "11111111-2222-3333-4444-555555555555",
                                              "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                              "categoryName": "VIP",
                                              "rowLabel": "A",
                                              "seatNumber": 12,
                                              "priceAtBooking": 100.00,
                                              "ticketNumber": "20260502-12345A",
                                              "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                            },
                                            {
                                              "seatId": "22222222-3333-4444-5555-666666666666",
                                              "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                              "categoryName": "VIP",
                                              "rowLabel": "A",
                                              "seatNumber": 13,
                                              "priceAtBooking": 100.00,
                                              "ticketNumber": "20260502-12346B",
                                              "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                            }
                                          ],
                                          "createdAt": "2026-05-02T15:45:00",
                                          "updatedAt": "2026-05-02T15:50:00"
                                        }
                                      ]
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT")
    })
    public ResponseEntity<ApiResult<List<BookingResponseDTO>>> getMyBookings(Authentication authentication) {
        String userEmail = authentication.getName();
        log.debug("GET /bookings/my userEmail={}", userEmail);
        List<BookingResponseDTO> result = bookingService.getMyBookings(userEmail);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.error(HttpStatus.NOT_FOUND, "Not found"));
        }
        return ResponseEntity.ok(ApiResult.ok("Bookings retrieved successfully", result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get booking by id", description = "Returns a specific booking if it belongs to the authenticated user.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Booking returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking by id", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Booking retrieved successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "CONFIRMED",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T15:50:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Booking not found")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> getBookingById(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.debug("GET /bookings/{} userEmail={} isAdmin={}", id, userEmail, isAdmin);
        return ResponseEntity.ok(ApiResult.ok("Booking retrieved successfully",
                bookingService.getBookingById(id, userEmail, isAdmin)));
    }

    private static boolean hasRole(Authentication authentication, String role) {
        if (authentication == null || authentication.getAuthorities() == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }

    @GetMapping("/by-category/{categoryId}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "List bookings by seat category",
            description = "Analytics endpoint. Returns one row per booked seat in the given category, " +
                    "including who bought it and when. Includes CANCELLED bookings — filter client-side if you need " +
                    "to exclude them. Restricted to EVENT_ORGANIZER because it exposes customer emails."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Bookings returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CategoryBookingDTO.class),
                            examples = @ExampleObject(name = "Bookings by category", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Bookings retrieved successfully",
                                      "data": [
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
                                          "status": "CANCELLED",
                                          "confirmationNumber": "INN-20260501-EF34GH",
                                          "seatId": "22222222-3333-4444-5555-666666666666",
                                          "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                          "categoryName": "VIP",
                                          "rowLabel": "A",
                                          "seatNumber": 13,
                                          "ticketNumber": "20260501-67890B",
                                          "priceAtBooking": 100.00,
                                          "bookedAt": "2026-05-01T10:30:00",
                                          "updatedAt": "2026-05-01T11:00:00",
                                          "expiresAt": null
                                        }
                                      ]
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<List<CategoryBookingDTO>>> getBookingsByCategory(
            @PathVariable UUID categoryId,
            Authentication authentication) {
        String requesterEmail = authentication.getName();
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.debug("GET /bookings/by-category/{} requesterEmail={} isAdmin={}",
                categoryId, requesterEmail, isAdmin);
        return ResponseEntity.ok(ApiResult.ok("Bookings retrieved successfully",
                bookingService.getBookingsByCategory(categoryId, requesterEmail, isAdmin)));
    }

    @GetMapping("/by-event/{eventId}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "List bookings by event",
            description = "Analytics endpoint. Returns one row per booked seat across every category " +
                    "in the given event. Includes CANCELLED bookings — caller filters. " +
                    "Used by seat-service to build event-level analytics in a single round-trip. " +
                    "Restricted to EVENT_ORGANIZER because it exposes customer emails."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Bookings returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CategoryBookingDTO.class),
                            examples = @ExampleObject(name = "Bookings by event", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Bookings retrieved successfully",
                                      "data": [
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
                                          "bookingId": "c5d1e3f4-3456-7890-abcd-ef0123456789",
                                          "userEmail": "carol@example.com",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "status": "PENDING",
                                          "confirmationNumber": "INN-20260502-IJ56KL",
                                          "seatId": "33333333-4444-5555-6666-777777777777",
                                          "categoryId": "9e2c5b4f-2d1f-4a28-8b1c-2e5c8a7b6d22",
                                          "categoryName": "GA",
                                          "rowLabel": "F",
                                          "seatNumber": 4,
                                          "ticketNumber": "20260502-99999C",
                                          "priceAtBooking": 60.00,
                                          "bookedAt": "2026-05-02T14:00:00",
                                          "updatedAt": "2026-05-02T14:00:00",
                                          "expiresAt": "2026-05-02T14:05:00"
                                        }
                                      ]
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<List<CategoryBookingDTO>>> getBookingsByEvent(
            @PathVariable UUID eventId,
            Authentication authentication) {
        String requesterEmail = authentication.getName();
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.debug("GET /bookings/by-event/{} requesterEmail={} isAdmin={}",
                eventId, requesterEmail, isAdmin);
        return ResponseEntity.ok(ApiResult.ok("Bookings retrieved successfully",
                bookingService.getBookingsByEvent(eventId, requesterEmail, isAdmin)));
    }

    @GetMapping("/active-counts")
    @SecurityRequirements()
    @Operation(
            summary = "Active booking item counts per event",
            description = "Internal endpoint used by event-service to compute `availableTickets` " +
                    "(`totalCapacity − count`). Returns one row per supplied eventId that has at least " +
                    "one PENDING or CONFIRMED booking item; eventIds with no active bookings are absent " +
                    "from the response. CANCELLED bookings are excluded so released seats free up capacity."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Counts returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventActiveCountDTO.class),
                            examples = @ExampleObject(name = "Active counts", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Active booking counts retrieved successfully",
                                      "data": [
                                        { "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "count": 42 },
                                        { "eventId": "9b1c5d2e-8f3a-4b1c-a4d2-1e2c5d4f8a3b", "count": 7 }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<List<EventActiveCountDTO>>> getActiveCounts(
            @RequestParam("eventIds") List<UUID> eventIds) {
        log.debug("GET /bookings/active-counts eventIds={}", eventIds);
        return ResponseEntity.ok(ApiResult.ok("Active booking counts retrieved successfully",
                bookingService.getActiveItemCountsByEvents(eventIds)));
    }

    @GetMapping("/confirmation/{number}")
    @SecurityRequirements()
    @Operation(summary = "Lookup by confirmation number", description = "Public endpoint used to verify a booking by confirmation number.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Booking returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking by confirmation number", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Booking retrieved successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "CONFIRMED",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T15:50:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Confirmation number not found")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> getByConfirmationNumber(@PathVariable String number) {
        log.debug("GET /bookings/confirmation/{}", number);
        return ResponseEntity.ok(ApiResult.ok("Booking retrieved successfully",
                bookingService.getByConfirmationNumber(number)));
    }

    @GetMapping("/phone/{phoneNumber}")
    @SecurityRequirements()
    @Operation(
            summary = "Lookup bookings by phone number",
            description = "Public endpoint. Returns the active (PENDING / CONFIRMED) bookings attached " +
                    "to the given phone number, most recent first. Cancelled bookings are excluded. " +
                    "Returns an empty list if no bookings exist for that phone."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Bookings returned (may be empty)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Bookings by phone", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Bookings retrieved successfully",
                                      "data": [
                                        {
                                          "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                          "userEmail": "alice@example.com",
                                          "phoneNumber": "+254700000000",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "confirmationNumber": "INN-20260502-AB12CD",
                                          "status": "CONFIRMED",
                                          "totalAmount": 100.00,
                                          "items": [
                                            {
                                              "seatId": "11111111-2222-3333-4444-555555555555",
                                              "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                              "categoryName": "VIP",
                                              "rowLabel": "A",
                                              "seatNumber": 12,
                                              "priceAtBooking": 100.00,
                                              "ticketNumber": "20260502-12345A",
                                              "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                            }
                                          ],
                                          "createdAt": "2026-05-02T15:45:00",
                                          "updatedAt": "2026-05-02T15:50:00",
                                          "expiresAt": null
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<List<BookingResponseDTO>>> getBookingsByPhoneNumber(
            @PathVariable String phoneNumber) {
        log.debug("GET /bookings/phone/{}", phoneNumber);
        return ResponseEntity.ok(ApiResult.ok("Bookings retrieved successfully",
                bookingService.getActiveByPhoneNumber(phoneNumber)));
    }

    @PatchMapping("/{id}/cancel")
    @MinTier(2)
    @Operation(summary = "Cancel booking", description = "Cancels a booking before payment confirmation. Requires tier 2.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Booking cancelled",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking cancelled", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Booking cancelled successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "CANCELLED",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T16:00:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Cannot cancel in current state")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> cancelBooking(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.info("PATCH /bookings/{}/cancel userEmail={} isAdmin={}", id, userEmail, isAdmin);
        return ResponseEntity.ok(ApiResult.ok("Booking cancelled successfully",
                bookingService.cancelBooking(id, userEmail, isAdmin)));
    }

    @PatchMapping("/{id}/confirm")
    @Operation(summary = "Confirm booking", description = "Marks a booking as confirmed (typically called by payment flow).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Booking confirmed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking confirmed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Booking confirmed successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "CONFIRMED",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T15:50:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid booking state")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> confirmBooking(
            @PathVariable UUID id,
            @RequestBody(required = false) ConfirmBookingRequestDTO request
    ) {
        log.info("PATCH /bookings/{}/confirm pointsToUse={} cashAmount={}", id,
                request == null ? null : request.getPointsToUse(),
                request == null ? null : request.getCashAmount());
        return ResponseEntity.ok(ApiResult.ok("Booking confirmed successfully",
                bookingService.confirmBooking(id, request)));
    }
}
