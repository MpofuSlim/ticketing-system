package com.innbucks.seatservice.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Aggregated analytics for one seat category. Combines:
//   - the category record itself (price, total/available counters),
//   - per-status seat counts from seat-service's seats table,
//   - bookings + revenue from booking-service.
//
// `bookingServiceReachable=false` means booking-service was down at fetch
// time — the booking sub-fields then reflect what we know from seat-service
// alone (zeros / empty list) so callers can render the rest of the page.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatCategoryAnalyticsDTO {

    private CategoryInfo category;
    private SeatStatusCounts seatStatusCounts;
    private BookingStats bookings;
    private boolean bookingServiceReachable;
    private LocalDateTime fetchedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryInfo {
        private UUID id;
        private UUID eventId;
        private String name;
        private String description;
        private BigDecimal price;
        private Integer totalSeats;
        // Counter maintained on the SeatCategory row (decrement/increment via
        // SeatCategoryRepository). Compare with seatStatusCounts.available
        // (the live count from the seats table) to spot drift.
        private Integer cachedAvailableSeats;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatStatusCounts {
        private long total;
        private long available;
        private long locked;
        private long booked;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookingStats {
        private int totalRecords;
        private int activeRecords;     // PENDING + CONFIRMED
        private int cancelledRecords;
        private BigDecimal grossRevenue;     // sum priceAtBooking across all records
        private BigDecimal netRevenue;       // sum priceAtBooking for non-CANCELLED only
        private BigDecimal potentialRevenue; // totalSeats × price (max if every seat sold)
        private LocalDateTime mostRecentBookingAt;
        // All bookings for the category, most recent first. Includes CANCELLED.
        private List<CategoryBookingDTO> items;
    }
}
