package com.innbucks.bookingservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CreateBookingRequestDTO {

    @NotNull(message = "Event ID is required")
    private UUID eventId;

    // Optional. Online bookings made without a logged-in account send the
    // phone here so the booking can still be looked up later by phone. When
    // the request is authenticated, the JWT's phoneNumber claim wins and
    // this field is ignored.
    private String phoneNumber;

    @NotEmpty(message = "At least one seat is required")
    private List<@Valid SeatItemRequest> seats;

    // Each entry requests one seat in the given category. The actual seat is
    // picked at random by booking-service from seat-service's available pool.
    @Data
    public static class SeatItemRequest {
        @NotNull(message = "Category ID is required")
        private UUID categoryId;
    }
}
