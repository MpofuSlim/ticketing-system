package com.innbucks.seatservice.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Aggregated analytics for a whole event. Combines:
//   - per-category breakdowns (category metadata, seat-status counts,
//     paginated bookings + per-category revenue), and
//   - event-level rollup totals across every category in the event.
//
// `bookingServiceReachable=false` means booking-service was down at fetch
// time — the booking sub-fields then reflect what we know from seat-service
// alone (zeros / empty list) so callers can render the rest of the page.
//
// Pagination: `page` and `size` query params apply per-category; each
// category's `bookings.items` list is sliced to that page. The aggregate
// counts and revenue numbers above `items` are always computed across the
// full set, so totals don't change as the consumer pages through.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventAnalyticsDTO {

    private UUID eventId;
    private int categoryCount;
    private EventTotals totals;
    private List<CategoryAnalytics> categories;
    private boolean bookingServiceReachable;
    private LocalDateTime fetchedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventTotals {
        // Seats summed across every category's seats table.
        private long totalSeats;
        private long availableSeats;
        private long lockedSeats;
        private long bookedSeats;
        // Booking record counts across every category in the event.
        private int totalBookings;
        private int activeBookings;     // PENDING + CONFIRMED
        private int cancelledBookings;
        private BigDecimal grossRevenue;     // sum priceAtBooking across all records
        private BigDecimal netRevenue;       // sum priceAtBooking for non-CANCELLED
        private BigDecimal potentialRevenue; // sum (totalSeats × price) per category
        private LocalDateTime mostRecentBookingAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryAnalytics {
        private CategoryInfo category;
        private SeatStatusCounts seatStatusCounts;
        private BookingStats bookings;
    }

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
        // Aggregates across the FULL set, not just the current page.
        private int totalRecords;
        private int activeRecords;     // PENDING + CONFIRMED
        private int cancelledRecords;
        private BigDecimal grossRevenue;     // sum priceAtBooking across all records
        private BigDecimal netRevenue;       // sum priceAtBooking for non-CANCELLED only
        private BigDecimal potentialRevenue; // totalSeats × price (max if every seat sold)
        private LocalDateTime mostRecentBookingAt;
        // Pagination metadata for the items slice below.
        private int pageNumber;
        private int pageSize;
        private int totalPages;
        // Page-sized slice of bookings, most recent first. Includes CANCELLED.
        private List<CategoryBookingDTO> items;
    }
}
