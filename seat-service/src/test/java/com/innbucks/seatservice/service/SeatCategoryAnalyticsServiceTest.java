package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.dto.CategoryBookingDTO;
import com.innbucks.seatservice.dto.EventAnalyticsDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SeatCategoryAnalyticsServiceTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID CATEGORY_VIP = UUID.randomUUID();
    private static final UUID CATEGORY_GA  = UUID.randomUUID();

    private SeatCategory category(UUID id, String name, int total, int cachedAvailable, BigDecimal price) {
        return SeatCategory.builder()
                .id(id)
                .eventId(EVENT_ID)
                .name(name)
                .description(name + " seats")
                .price(price)
                .totalSeats(total)
                .availableSeats(cachedAvailable)
                .deleted(false)
                .createdAt(LocalDateTime.now().minusDays(7))
                .updatedAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    private Seat seat(Seat.SeatStatus status) {
        return Seat.builder().id(UUID.randomUUID()).status(status).build();
    }

    private CategoryBookingDTO booking(UUID categoryId,
                                       CategoryBookingDTO.BookingStatus status,
                                       BigDecimal price,
                                       LocalDateTime bookedAt) {
        return CategoryBookingDTO.builder()
                .bookingId(UUID.randomUUID())
                .userEmail("u@example.com")
                .eventId(EVENT_ID)
                .status(status)
                .seatId(UUID.randomUUID())
                .categoryId(categoryId)
                .priceAtBooking(price)
                .bookedAt(bookedAt)
                .build();
    }

    private SeatCategoryAnalyticsService newService(SeatCategoryRepository categoryRepo,
                                                     SeatRepository seatRepo,
                                                     BookingServiceClient bookingClient) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.innbucks.seatservice.client.EventServiceClient> eventClientProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        return new SeatCategoryAnalyticsService(categoryRepo, seatRepo, bookingClient, eventClientProvider);
    }

    @Test
    void getEventAnalytics_returnsEmptyShellWhenEventHasNoCategories() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);
        when(categoryRepo.findByEventIdAndDeletedFalse(EVENT_ID)).thenReturn(List.of());
        when(bookingClient.fetchBookingsByEvent(eq(EVENT_ID), any())).thenReturn(Optional.of(List.of()));

        EventAnalyticsDTO result = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, 0, 20, "Bearer x");

        assertEquals(0, result.getCategoryCount());
        assertTrue(result.getCategories().isEmpty());
        assertEquals(0L, result.getTotals().getTotalSeats());
        assertEquals(0, result.getTotals().getTotalBookings());
    }

    @Test
    void getEventAnalytics_aggregatesSeatStatusCountsAcrossAllCategories() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        when(categoryRepo.findByEventIdAndDeletedFalse(EVENT_ID)).thenReturn(List.of(
                category(CATEGORY_VIP, "VIP", 4, 2, new BigDecimal("100.00")),
                category(CATEGORY_GA,  "GA",  6, 3, new BigDecimal("50.00"))
        ));
        // Seat-table counts are not consulted when booking-service is reachable;
        // status counts are derived live from booking items below.
        when(bookingClient.fetchBookingsByEvent(eq(EVENT_ID), any())).thenReturn(Optional.of(List.of(
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CONFIRMED, new BigDecimal("100.00"), LocalDateTime.now()),
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.PENDING,   new BigDecimal("100.00"), LocalDateTime.now()),
                booking(CATEGORY_GA,  CategoryBookingDTO.BookingStatus.CONFIRMED, new BigDecimal("50.00"),  LocalDateTime.now()),
                booking(CATEGORY_GA,  CategoryBookingDTO.BookingStatus.CONFIRMED, new BigDecimal("50.00"),  LocalDateTime.now()),
                booking(CATEGORY_GA,  CategoryBookingDTO.BookingStatus.CONFIRMED, new BigDecimal("50.00"),  LocalDateTime.now())
        )));

        EventAnalyticsDTO result = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, 0, 20, null);

        assertEquals(2, result.getCategoryCount());
        // Rollup: total seats 10, available 5, locked 1, booked 4
        assertEquals(10L, result.getTotals().getTotalSeats());
        assertEquals(5L, result.getTotals().getAvailableSeats());
        assertEquals(1L, result.getTotals().getLockedSeats());
        assertEquals(4L, result.getTotals().getBookedSeats());
        assertTrue(result.isBookingServiceReachable());
    }

    @Test
    void getEventAnalytics_groupsBookingsByCategoryAndComputesPerBlockRevenue() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        when(categoryRepo.findByEventIdAndDeletedFalse(EVENT_ID)).thenReturn(List.of(
                category(CATEGORY_VIP, "VIP", 5, 5, new BigDecimal("100.00")),
                category(CATEGORY_GA,  "GA",  10, 10, new BigDecimal("50.00"))
        ));
        when(seatRepo.findByCategoryId(any())).thenReturn(List.of());
        when(bookingClient.fetchBookingsByEvent(eq(EVENT_ID), any())).thenReturn(Optional.of(List.of(
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CONFIRMED, new BigDecimal("100.00"), LocalDateTime.now().minusDays(2)),
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CANCELLED, new BigDecimal("100.00"), LocalDateTime.now().minusDays(3)),
                booking(CATEGORY_GA,  CategoryBookingDTO.BookingStatus.CONFIRMED, new BigDecimal("50.00"),  LocalDateTime.now().minusDays(1)),
                booking(CATEGORY_GA,  CategoryBookingDTO.BookingStatus.PENDING,   new BigDecimal("50.00"),  LocalDateTime.now())
        )));

        EventAnalyticsDTO result = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, 0, 20, null);

        EventAnalyticsDTO.CategoryAnalytics vip = result.getCategories().stream()
                .filter(c -> c.getCategory().getId().equals(CATEGORY_VIP))
                .findFirst().orElseThrow();
        EventAnalyticsDTO.CategoryAnalytics ga = result.getCategories().stream()
                .filter(c -> c.getCategory().getId().equals(CATEGORY_GA))
                .findFirst().orElseThrow();

        // Per-category revenue split — PENDING is held money, CONFIRMED is real.
        // VIP: 1 CONFIRMED @100 + 1 CANCELLED → paid=100, pending=0.
        assertEquals(0, new BigDecimal("100.00").compareTo(vip.getBookings().getPaidRevenue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vip.getBookings().getPendingRevenue()));
        assertEquals(0, new BigDecimal("500.00").compareTo(vip.getBookings().getPotentialRevenue())); // 5 × 100
        // GA: 1 CONFIRMED @50 + 1 PENDING @50 → paid=50, pending=50.
        assertEquals(0, new BigDecimal("50.00").compareTo(ga.getBookings().getPaidRevenue()));
        assertEquals(0, new BigDecimal("50.00").compareTo(ga.getBookings().getPendingRevenue()));
        assertEquals(0, new BigDecimal("500.00").compareTo(ga.getBookings().getPotentialRevenue())); // 10 × 50

        // Event rollup adds per-category numbers.
        assertEquals(0, new BigDecimal("150.00").compareTo(result.getTotals().getPaidRevenue()));
        assertEquals(0, new BigDecimal("50.00").compareTo(result.getTotals().getPendingRevenue()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(result.getTotals().getPotentialRevenue()));
        assertEquals(4, result.getTotals().getTotalBookings());
        assertEquals(2, result.getTotals().getPaidBookings());
        assertEquals(1, result.getTotals().getPendingBookings());
        assertEquals(1, result.getTotals().getCancelledBookings());
    }

    @Test
    void getEventAnalytics_splitsRevenueIntoPendingAndPaidBuckets() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        when(categoryRepo.findByEventIdAndDeletedFalse(EVENT_ID)).thenReturn(List.of(
                category(CATEGORY_VIP, "VIP", 5, 0, new BigDecimal("100.00"))
        ));
        when(seatRepo.findByCategoryId(any())).thenReturn(List.of());
        when(bookingClient.fetchBookingsByEvent(eq(EVENT_ID), any())).thenReturn(Optional.of(List.of(
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.PENDING,   new BigDecimal("100.00"), LocalDateTime.now().minusMinutes(1)),
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.PENDING,   new BigDecimal("100.00"), LocalDateTime.now().minusMinutes(2)),
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CONFIRMED, new BigDecimal("100.00"), LocalDateTime.now().minusDays(1)),
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CANCELLED, new BigDecimal("100.00"), LocalDateTime.now().minusDays(3))
        )));

        EventAnalyticsDTO result = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, 0, 20, null);

        EventAnalyticsDTO.BookingStats stats = result.getCategories().get(0).getBookings();
        // Counts: 4 total = 2 pending + 1 paid + 1 cancelled. PENDING is a
        // hold, not a real booking — there is no "active" rollup field.
        assertEquals(4, stats.getTotalRecords());
        assertEquals(2, stats.getPendingRecords());
        assertEquals(1, stats.getPaidRecords());
        assertEquals(1, stats.getCancelledRecords());
        // Revenue: pendingRevenue=200 (held), paidRevenue=100 (real money in).
        // Cancelled records contribute to neither.
        assertEquals(0, new BigDecimal("200.00").compareTo(stats.getPendingRevenue()));
        assertEquals(0, new BigDecimal("100.00").compareTo(stats.getPaidRevenue()));

        // Event rollup carries the same buckets through.
        assertEquals(2, result.getTotals().getPendingBookings());
        assertEquals(1, result.getTotals().getPaidBookings());
        assertEquals(0, new BigDecimal("200.00").compareTo(result.getTotals().getPendingRevenue()));
        assertEquals(0, new BigDecimal("100.00").compareTo(result.getTotals().getPaidRevenue()));
    }

    @Test
    void getEventAnalytics_paginatesBookingsListPerCategoryAndKeepsAggregatesAcrossFullSet() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        when(categoryRepo.findByEventIdAndDeletedFalse(EVENT_ID)).thenReturn(List.of(
                category(CATEGORY_VIP, "VIP", 5, 5, BigDecimal.TEN)
        ));
        when(seatRepo.findByCategoryId(any())).thenReturn(List.of());
        // 5 confirmed bookings, increasing booked-at timestamps.
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
        when(bookingClient.fetchBookingsByEvent(eq(EVENT_ID), any())).thenReturn(Optional.of(List.of(
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CONFIRMED, BigDecimal.TEN, t0.plusDays(1)),
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CONFIRMED, BigDecimal.TEN, t0.plusDays(2)),
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CONFIRMED, BigDecimal.TEN, t0.plusDays(3)),
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CONFIRMED, BigDecimal.TEN, t0.plusDays(4)),
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CONFIRMED, BigDecimal.TEN, t0.plusDays(5))
        )));

        // page=0, size=2 → first 2 (most recent first), totalRecords still 5.
        EventAnalyticsDTO firstPage = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, 0, 2, null);
        EventAnalyticsDTO.BookingStats stats = firstPage.getCategories().get(0).getBookings();
        assertEquals(5, stats.getTotalRecords());
        assertEquals(0, stats.getPageNumber());
        assertEquals(2, stats.getPageSize());
        assertEquals(3, stats.getTotalPages()); // ceil(5/2)
        assertEquals(2, stats.getItems().size());
        assertEquals(t0.plusDays(5), stats.getItems().get(0).getBookedAt());
        assertEquals(t0.plusDays(4), stats.getItems().get(1).getBookedAt());

        // page=2, size=2 → last partial page.
        EventAnalyticsDTO lastPage = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, 2, 2, null);
        assertEquals(1, lastPage.getCategories().get(0).getBookings().getItems().size());

        // page out of range → empty items but stats unchanged.
        EventAnalyticsDTO past = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, 99, 2, null);
        assertEquals(5, past.getCategories().get(0).getBookings().getTotalRecords());
        assertTrue(past.getCategories().get(0).getBookings().getItems().isEmpty());
    }

    @Test
    void getEventAnalytics_clampsPageSizeAndPageIndex() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        when(categoryRepo.findByEventIdAndDeletedFalse(EVENT_ID)).thenReturn(List.of(
                category(CATEGORY_VIP, "VIP", 5, 5, BigDecimal.TEN)
        ));
        when(seatRepo.findByCategoryId(any())).thenReturn(List.of());
        when(bookingClient.fetchBookingsByEvent(eq(EVENT_ID), any())).thenReturn(Optional.of(List.of()));

        // Negative page → 0; size > MAX → MAX_PAGE_SIZE; size < 1 → 1.
        EventAnalyticsDTO huge = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, -5, 10_000, null);
        EventAnalyticsDTO.BookingStats stats = huge.getCategories().get(0).getBookings();
        assertEquals(0, stats.getPageNumber());
        assertEquals(SeatCategoryAnalyticsService.MAX_PAGE_SIZE, stats.getPageSize());

        EventAnalyticsDTO tiny = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, 0, 0, null);
        assertEquals(1, tiny.getCategories().get(0).getBookings().getPageSize());
    }

    @Test
    void getEventAnalytics_degradesGracefullyWhenBookingServiceUnreachable() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        when(categoryRepo.findByEventIdAndDeletedFalse(EVENT_ID)).thenReturn(List.of(
                category(CATEGORY_VIP, "VIP", 10, 4, new BigDecimal("20.00"))
        ));
        when(seatRepo.findByCategoryId(any())).thenReturn(List.of(seat(Seat.SeatStatus.AVAILABLE)));
        when(bookingClient.fetchBookingsByEvent(eq(EVENT_ID), any())).thenReturn(Optional.empty());

        EventAnalyticsDTO result = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, 0, 20, null);

        assertFalse(result.isBookingServiceReachable());
        assertEquals(0, result.getTotals().getTotalBookings());
        assertEquals(1, result.getCategoryCount());
        // Potential revenue still computable from local category data.
        assertEquals(0, new BigDecimal("200.00").compareTo(
                result.getCategories().get(0).getBookings().getPotentialRevenue()));
    }

    @Test
    void getEventAnalytics_includesBothCachedAndLiveAvailableCounts() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        // cachedAvailable=4 disagrees with the live count derived from bookings:
        // category has 10 seats and 2 are CONFIRMED, so 8 are actually available.
        when(categoryRepo.findByEventIdAndDeletedFalse(EVENT_ID)).thenReturn(List.of(
                category(CATEGORY_VIP, "VIP", 10, 4, BigDecimal.TEN)
        ));
        when(bookingClient.fetchBookingsByEvent(eq(EVENT_ID), any())).thenReturn(Optional.of(List.of(
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CONFIRMED, BigDecimal.TEN, LocalDateTime.now()),
                booking(CATEGORY_VIP, CategoryBookingDTO.BookingStatus.CONFIRMED, BigDecimal.TEN, LocalDateTime.now())
        )));

        EventAnalyticsDTO result = newService(categoryRepo, seatRepo, bookingClient)
                .getEventAnalytics(EVENT_ID, "tester@example.com", true, 0, 20, null);

        EventAnalyticsDTO.CategoryAnalytics vip = result.getCategories().get(0);
        assertEquals(4, vip.getCategory().getCachedAvailableSeats());
        assertEquals(8, vip.getSeatStatusCounts().getAvailable());
        assertEquals(2, vip.getSeatStatusCounts().getBooked());
        assertNotNull(result.getFetchedAt());
    }
}
