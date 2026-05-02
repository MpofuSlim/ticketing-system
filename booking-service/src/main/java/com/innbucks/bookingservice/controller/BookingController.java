package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CategoryBookingDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.security.MinTier;
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

    @PostMapping
    @MinTier(2)
    @Operation(summary = "Create booking", description = "Creates a new pending booking for the authenticated user. Requires tier 2 (ID-verified customers).")
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
                                            "ticketNumber": "20260502-12345A"
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
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        int tier = extractTier(authentication);
        log.info("POST /bookings userEmail={} tier={} eventId={} seats={}",
                userEmail, tier, request.getEventId(), request.getSeats().size());
        BookingResponseDTO created = bookingService.createBooking(userEmail, tier, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Booking created successfully", created));
    }

    private int extractTier(Authentication authentication) {
        int tier = 0;
        for (var authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("TIER_")) {
                try {
                    tier = Math.max(tier, Integer.parseInt(name.substring(5)));
                } catch (NumberFormatException ignored) { }
            }
        }
        return tier;
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
                                              "ticketNumber": "20260502-12345A"
                                            },
                                            {
                                              "seatId": "22222222-3333-4444-5555-666666666666",
                                              "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                              "categoryName": "VIP",
                                              "rowLabel": "A",
                                              "seatNumber": 13,
                                              "priceAtBooking": 100.00,
                                              "ticketNumber": "20260502-12346B"
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
                                            "ticketNumber": "20260502-12345A"
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
        log.debug("GET /bookings/{} userEmail={}", id, userEmail);
        return ResponseEntity.ok(ApiResult.ok("Booking retrieved successfully",
                bookingService.getBookingById(id, userEmail)));
    }

    @GetMapping("/by-category/{categoryId}")
    @PreAuthorize("hasAnyRole('TENANT','ADMIN')")
    @Operation(
            summary = "List bookings by seat category",
            description = "Analytics endpoint. Returns one row per booked seat in the given category, " +
                    "including who bought it and when. Includes CANCELLED bookings — filter client-side if you need " +
                    "to exclude them. Restricted to TENANT/ADMIN because it exposes customer emails."
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not TENANT/ADMIN")
    })
    public ResponseEntity<ApiResult<List<CategoryBookingDTO>>> getBookingsByCategory(@PathVariable UUID categoryId) {
        log.debug("GET /bookings/by-category/{}", categoryId);
        return ResponseEntity.ok(ApiResult.ok("Bookings retrieved successfully",
                bookingService.getBookingsByCategory(categoryId)));
    }

    @GetMapping("/by-event/{eventId}")
    @PreAuthorize("hasAnyRole('TENANT','ADMIN')")
    @Operation(
            summary = "List bookings by event",
            description = "Analytics endpoint. Returns one row per booked seat across every category " +
                    "in the given event. Includes CANCELLED bookings — caller filters. " +
                    "Used by seat-service to build event-level analytics in a single round-trip. " +
                    "Restricted to TENANT/ADMIN because it exposes customer emails."
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not TENANT/ADMIN")
    })
    public ResponseEntity<ApiResult<List<CategoryBookingDTO>>> getBookingsByEvent(@PathVariable UUID eventId) {
        log.debug("GET /bookings/by-event/{}", eventId);
        return ResponseEntity.ok(ApiResult.ok("Bookings retrieved successfully",
                bookingService.getBookingsByEvent(eventId)));
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
                                            "ticketNumber": "20260502-12345A"
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
                                            "ticketNumber": "20260502-12345A"
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
        log.info("PATCH /bookings/{}/cancel userEmail={}", id, userEmail);
        return ResponseEntity.ok(ApiResult.ok("Booking cancelled successfully",
                bookingService.cancelBooking(id, userEmail)));
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
                                            "ticketNumber": "20260502-12345A"
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
    public ResponseEntity<ApiResult<BookingResponseDTO>> confirmBooking(@PathVariable UUID id) {
        log.info("PATCH /bookings/{}/confirm", id);
        return ResponseEntity.ok(ApiResult.ok("Booking confirmed successfully",
                bookingService.confirmBooking(id)));
    }
}
