package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(name = "CreateBookingRequest")
public class CreateBookingRequestDTO {

    @Schema(example = "dc74382d-26ab-431d-b049-1c3a6d8dba51",
            description = "UUID of the event being booked.")
    @NotNull(message = "Event ID is required")
    private UUID eventId;

    // Optional. Guest web bookings (no JWT) send userEmail and phoneNumber
    // here so the booking can be looked up later. When the request is
    // authenticated, the JWT's email/phone claims win and these fields are
    // ignored.
    @Schema(example = "alice@example.com", nullable = true,
            description = "Only required for guest (unauthenticated) bookings. Ignored when a JWT is present.")
    private String userEmail;

    @Schema(example = "+263771234567", nullable = true,
            description = "Only required for guest (unauthenticated) bookings. Ignored when a JWT is present.")
    private String phoneNumber;

    @Schema(description = "One entry per seat to book. The service picks a random available seat in each category.")
    @NotEmpty(message = "At least one seat is required")
    private List<@Valid SeatItemRequest> seats;

    // Each entry requests one seat in the given category. The actual seat is
    // picked at random by booking-service from seat-service's available pool.
    @Data
    @Schema(name = "SeatItemRequest",
            description = "Requests one seat from the given category. The specific seat is chosen randomly.")
    public static class SeatItemRequest {

        @Schema(example = "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                description = "UUID of the seat category (VIP, GA, etc.).")
        @NotNull(message = "Category ID is required")
        private UUID categoryId;
    }
}
