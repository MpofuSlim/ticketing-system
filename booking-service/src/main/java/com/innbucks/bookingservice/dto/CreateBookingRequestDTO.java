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

    @NotEmpty(message = "At least one seat is required")
    private List<@Valid SeatItemRequest> seats;

    @Data
    public static class SeatItemRequest {
        @NotNull(message = "Seat ID is required")
        private UUID seatId;
    }
}
