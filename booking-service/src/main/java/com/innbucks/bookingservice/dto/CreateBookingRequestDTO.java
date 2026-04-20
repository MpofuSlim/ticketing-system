package com.innbucks.bookingservice.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class CreateBookingRequestDTO {

    @NotNull(message = "Event ID is required")
    private UUID eventId;

    @NotEmpty(message = "At least one seat is required")
    private List<SeatItemRequest> seats;

    @Data
    public static class SeatItemRequest {
        @NotNull private UUID seatId;
        @NotNull private UUID categoryId;
        @NotBlank private String rowLabel;
        @NotNull private Integer seatNumber;
        @NotBlank private String categoryName;
        @NotNull private BigDecimal priceAtBooking;
    }
}
