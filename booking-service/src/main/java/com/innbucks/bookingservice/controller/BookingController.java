package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@Tag(name = "Bookings", description = "Create, query, confirm, and cancel bookings.")
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    @Operation(summary = "Create booking", description = "Creates a new pending booking for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Booking created"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "400", description = "Validation or domain error")
    })
    public ResponseEntity<BookingResponseDTO> createBooking(
            @Valid @RequestBody CreateBookingRequestDTO request,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bookingService.createBooking(userEmail, request));
    }

    @GetMapping("/my")
    @Operation(summary = "List my bookings", description = "Returns all bookings for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bookings returned"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT")
    })
    public ResponseEntity<List<BookingResponseDTO>> getMyBookings(Authentication authentication) {
        return ResponseEntity.ok(bookingService.getMyBookings(authentication.getName()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get booking by id", description = "Returns a specific booking if it belongs to the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Booking returned"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "400", description = "Booking not found or access denied")
    })
    public ResponseEntity<BookingResponseDTO> getBookingById(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(bookingService.getBookingById(id, authentication.getName()));
    }

    @GetMapping("/confirmation/{number}")
    @SecurityRequirements()
    @Operation(summary = "Lookup by confirmation number", description = "Public endpoint used to verify a booking by confirmation number.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Booking returned"),
            @ApiResponse(responseCode = "400", description = "Confirmation number not found")
    })
    public ResponseEntity<BookingResponseDTO> getByConfirmationNumber(@PathVariable String number) {
        return ResponseEntity.ok(bookingService.getByConfirmationNumber(number));
    }

    @PatchMapping("/{id}/cancel")
    @Operation(summary = "Cancel booking", description = "Cancels a booking before payment confirmation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Booking cancelled"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "400", description = "Cannot cancel in current state")
    })
    public ResponseEntity<BookingResponseDTO> cancelBooking(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(bookingService.cancelBooking(id, authentication.getName()));
    }

    @PatchMapping("/{id}/confirm")
    @Operation(summary = "Confirm booking", description = "Marks a booking as confirmed (typically called by payment flow).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Booking confirmed"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "400", description = "Invalid booking state")
    })
    public ResponseEntity<BookingResponseDTO> confirmBooking(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingService.confirmBooking(id));
    }
}
