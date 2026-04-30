package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import com.innbucks.bookingservice.exception.UserServiceUnavailableException;
import com.innbucks.bookingservice.security.MinTier;
import com.innbucks.bookingservice.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final UserServiceClient userServiceClient;

    @PostMapping
    @MinTier(2)
    @Operation(summary = "Create booking", description = "Creates a new pending booking for the authenticated user. Requires tier 2 (ID-verified customers).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Booking created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Customer tier below minimum. Envelope: { code: '422', message: <reason>, data: { requiredTier, currentTier } }"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation or domain error")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> createBooking(
            @Valid @RequestBody CreateBookingRequestDTO request,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        // Tier is read live from user-service (DB) rather than from the JWT claim,
        // so a customer who's just upgraded but is still using an older token
        // is treated as their current tier. The interceptor has already gated
        // access via @MinTier(2); this lookup informs the per-tier seat cap.
        int tier = lookupCurrentTier(userEmail);
        log.info("POST /bookings userEmail={} tier={} eventId={} seats={}",
                userEmail, tier, request.getEventId(), request.getSeats().size());
        BookingResponseDTO created = bookingService.createBooking(userEmail, tier, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Booking created successfully", created));
    }

    private int lookupCurrentTier(String subject) {
        ApiResult<CustomerTierResponseDTO> envelope = userServiceClient.lookupCustomerTier(subject);
        if (envelope == null || envelope.getData() == null) {
            throw new UserServiceUnavailableException(
                    "Unable to verify customer tier — user-service is currently unavailable.");
        }
        return envelope.getData().getCurrentTier();
    }

    @GetMapping("/my")
    @Operation(summary = "List my bookings", description = "Returns all bookings for the authenticated user.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Bookings returned"),
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Booking returned"),
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

    @GetMapping("/confirmation/{number}")
    @SecurityRequirements()
    @Operation(summary = "Lookup by confirmation number", description = "Public endpoint used to verify a booking by confirmation number.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Booking returned"),
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Booking cancelled"),
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Booking confirmed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid booking state")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> confirmBooking(@PathVariable UUID id) {
        log.info("PATCH /bookings/{}/confirm", id);
        return ResponseEntity.ok(ApiResult.ok("Booking confirmed successfully",
                bookingService.confirmBooking(id)));
    }
}
