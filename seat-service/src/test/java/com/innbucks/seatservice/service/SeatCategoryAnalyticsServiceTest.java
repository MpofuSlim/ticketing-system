package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.dto.CategoryBookingDTO;
import com.innbucks.seatservice.dto.SeatCategoryAnalyticsDTO;
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

    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    private SeatCategory category(int total, int cachedAvailable, BigDecimal price) {
        return SeatCategory.builder()
                .id(CATEGORY_ID)
                .eventId(EVENT_ID)
                .name("VIP")
                .description("Front rows")
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

    private CategoryBookingDTO booking(CategoryBookingDTO.BookingStatus status, BigDecimal price, LocalDateTime bookedAt) {
        return CategoryBookingDTO.builder()
                .bookingId(UUID.randomUUID())
                .userEmail("u@example.com")
                .eventId(EVENT_ID)
                .status(status)
                .seatId(UUID.randomUUID())
                .categoryId(CATEGORY_ID)
                .priceAtBooking(price)
                .bookedAt(bookedAt)
                .build();
    }

    @Test
    void getAnalytics_throwsWhenCategoryMissing() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);
        when(categoryRepo.findById(CATEGORY_ID)).thenReturn(Optional.empty());

        SeatCategoryAnalyticsService service = new SeatCategoryAnalyticsService(categoryRepo, seatRepo, bookingClient);

        assertThrows(RuntimeException.class, () -> service.getAnalytics(CATEGORY_ID, "Bearer x"));
        verify(bookingClient, never()).fetchBookingsByCategory(any(), any());
    }

    @Test
    void getAnalytics_treatsSoftDeletedCategoryAsMissing() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatCategory deleted = category(10, 10, BigDecimal.TEN);
        deleted.setDeleted(true);
        when(categoryRepo.findById(CATEGORY_ID)).thenReturn(Optional.of(deleted));

        SeatCategoryAnalyticsService service = new SeatCategoryAnalyticsService(
                categoryRepo, mock(SeatRepository.class), mock(BookingServiceClient.class));

        assertThrows(RuntimeException.class, () -> service.getAnalytics(CATEGORY_ID, null));
    }

    @Test
    void getAnalytics_aggregatesSeatStatusCountsFromSeatsTable() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        when(categoryRepo.findById(CATEGORY_ID)).thenReturn(Optional.of(category(10, 4, BigDecimal.TEN)));
        when(seatRepo.findByCategoryId(CATEGORY_ID)).thenReturn(List.of(
                seat(Seat.SeatStatus.AVAILABLE),
                seat(Seat.SeatStatus.AVAILABLE),
                seat(Seat.SeatStatus.AVAILABLE),
                seat(Seat.SeatStatus.AVAILABLE),
                seat(Seat.SeatStatus.LOCKED),
                seat(Seat.SeatStatus.LOCKED),
                seat(Seat.SeatStatus.BOOKED),
                seat(Seat.SeatStatus.BOOKED),
                seat(Seat.SeatStatus.BOOKED),
                seat(Seat.SeatStatus.BOOKED)
        ));
        when(bookingClient.fetchBookingsByCategory(eq(CATEGORY_ID), any())).thenReturn(Optional.of(List.of()));

        SeatCategoryAnalyticsService service = new SeatCategoryAnalyticsService(categoryRepo, seatRepo, bookingClient);
        SeatCategoryAnalyticsDTO result = service.getAnalytics(CATEGORY_ID, "Bearer x");

        assertEquals(10, result.getSeatStatusCounts().getTotal());
        assertEquals(4, result.getSeatStatusCounts().getAvailable());
        assertEquals(2, result.getSeatStatusCounts().getLocked());
        assertEquals(4, result.getSeatStatusCounts().getBooked());
        assertTrue(result.isBookingServiceReachable());
    }

    @Test
    void getAnalytics_computesGrossNetAndPotentialRevenue() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        // 10 total seats, price 50.00 → potential revenue 500.00
        when(categoryRepo.findById(CATEGORY_ID)).thenReturn(Optional.of(category(10, 6, new BigDecimal("50.00"))));
        when(seatRepo.findByCategoryId(CATEGORY_ID)).thenReturn(List.of());
        when(bookingClient.fetchBookingsByCategory(eq(CATEGORY_ID), any())).thenReturn(Optional.of(List.of(
                booking(CategoryBookingDTO.BookingStatus.CONFIRMED, new BigDecimal("50.00"), LocalDateTime.now().minusDays(2)),
                booking(CategoryBookingDTO.BookingStatus.PENDING,   new BigDecimal("50.00"), LocalDateTime.now().minusDays(1)),
                booking(CategoryBookingDTO.BookingStatus.CANCELLED, new BigDecimal("50.00"), LocalDateTime.now().minusDays(3))
        )));

        SeatCategoryAnalyticsService service = new SeatCategoryAnalyticsService(categoryRepo, seatRepo, bookingClient);
        SeatCategoryAnalyticsDTO result = service.getAnalytics(CATEGORY_ID, null);

        assertEquals(3, result.getBookings().getTotalRecords());
        assertEquals(2, result.getBookings().getActiveRecords());
        assertEquals(1, result.getBookings().getCancelledRecords());
        assertEquals(0, new BigDecimal("150.00").compareTo(result.getBookings().getGrossRevenue()));
        assertEquals(0, new BigDecimal("100.00").compareTo(result.getBookings().getNetRevenue()));
        assertEquals(0, new BigDecimal("500.00").compareTo(result.getBookings().getPotentialRevenue()));
    }

    @Test
    void getAnalytics_sortsBookingItemsMostRecentFirst() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);

        when(categoryRepo.findById(CATEGORY_ID)).thenReturn(Optional.of(category(5, 5, BigDecimal.TEN)));
        when(seatRepo.findByCategoryId(CATEGORY_ID)).thenReturn(List.of());
        when(bookingClient.fetchBookingsByCategory(eq(CATEGORY_ID), any())).thenReturn(Optional.of(List.of(
                booking(CategoryBookingDTO.BookingStatus.CONFIRMED, BigDecimal.TEN, t0.plusDays(1)),
                booking(CategoryBookingDTO.BookingStatus.CONFIRMED, BigDecimal.TEN, t0.plusDays(3)),
                booking(CategoryBookingDTO.BookingStatus.CONFIRMED, BigDecimal.TEN, t0.plusDays(2))
        )));

        SeatCategoryAnalyticsService service = new SeatCategoryAnalyticsService(categoryRepo, seatRepo, bookingClient);
        SeatCategoryAnalyticsDTO result = service.getAnalytics(CATEGORY_ID, null);

        List<CategoryBookingDTO> items = result.getBookings().getItems();
        assertEquals(t0.plusDays(3), items.get(0).getBookedAt());
        assertEquals(t0.plusDays(2), items.get(1).getBookedAt());
        assertEquals(t0.plusDays(1), items.get(2).getBookedAt());
        assertEquals(t0.plusDays(3), result.getBookings().getMostRecentBookingAt());
    }

    @Test
    void getAnalytics_degradesGracefullyWhenBookingServiceUnreachable() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        when(categoryRepo.findById(CATEGORY_ID)).thenReturn(Optional.of(category(10, 4, new BigDecimal("20.00"))));
        when(seatRepo.findByCategoryId(CATEGORY_ID)).thenReturn(List.of(seat(Seat.SeatStatus.AVAILABLE)));
        // Empty Optional → booking-service was unreachable
        when(bookingClient.fetchBookingsByCategory(eq(CATEGORY_ID), any())).thenReturn(Optional.empty());

        SeatCategoryAnalyticsService service = new SeatCategoryAnalyticsService(categoryRepo, seatRepo, bookingClient);
        SeatCategoryAnalyticsDTO result = service.getAnalytics(CATEGORY_ID, null);

        assertFalse(result.isBookingServiceReachable());
        assertEquals(0, result.getBookings().getTotalRecords());
        assertNotNull(result.getBookings().getItems());
        assertTrue(result.getBookings().getItems().isEmpty());
        // Potential revenue still computable from the local category record alone.
        assertEquals(0, new BigDecimal("200.00").compareTo(result.getBookings().getPotentialRevenue()));
    }

    @Test
    void getAnalytics_includesCategoryMetadataAndBothAvailableCounts() {
        SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient bookingClient = mock(BookingServiceClient.class);

        // cachedAvailable=4 disagrees with the actual seats-table count (8 AVAILABLE),
        // which is exactly the case operators want to see.
        when(categoryRepo.findById(CATEGORY_ID)).thenReturn(Optional.of(category(10, 4, BigDecimal.TEN)));
        when(seatRepo.findByCategoryId(CATEGORY_ID)).thenReturn(List.of(
                seat(Seat.SeatStatus.AVAILABLE), seat(Seat.SeatStatus.AVAILABLE),
                seat(Seat.SeatStatus.AVAILABLE), seat(Seat.SeatStatus.AVAILABLE),
                seat(Seat.SeatStatus.AVAILABLE), seat(Seat.SeatStatus.AVAILABLE),
                seat(Seat.SeatStatus.AVAILABLE), seat(Seat.SeatStatus.AVAILABLE)
        ));
        when(bookingClient.fetchBookingsByCategory(eq(CATEGORY_ID), any())).thenReturn(Optional.of(List.of()));

        SeatCategoryAnalyticsService service = new SeatCategoryAnalyticsService(categoryRepo, seatRepo, bookingClient);
        SeatCategoryAnalyticsDTO result = service.getAnalytics(CATEGORY_ID, null);

        assertEquals("VIP", result.getCategory().getName());
        assertEquals(EVENT_ID, result.getCategory().getEventId());
        assertEquals(10, result.getCategory().getTotalSeats());
        assertEquals(4, result.getCategory().getCachedAvailableSeats());
        assertEquals(8, result.getSeatStatusCounts().getAvailable());
        assertNotNull(result.getFetchedAt());
    }
}
