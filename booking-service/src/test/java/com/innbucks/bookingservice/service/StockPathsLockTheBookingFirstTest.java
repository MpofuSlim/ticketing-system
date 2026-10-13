package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.repository.ExpiryCandidate;
import com.innbucks.bookingservice.testsupport.RecordingTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Every path that RETURNS stock — and resurrect, which takes it back — takes
 * the booking row's lock before it touches a single counter row — the first link of the global lock order
 * ({@link StockLedger}). Taking a counter row first and the booking row second
 * is the shape that deadlocks against a path doing it the other way round, so
 * each path here is pinned to: lock the booking, then release, in ascending
 * category order. Confirm takes the same lock and touches no stock; it is
 * pinned in {@code BookingServiceTest}. Against real Postgres (the counter row
 * provably untouched while the booking row is held elsewhere) in
 * {@code BookingRowLockPostgresIT}.
 */
class StockPathsLockTheBookingFirstTest {

    // Postgres order: LOW < HIGH (Java's UUID.compareTo says the opposite).
    private static final UUID LOW = UUID.fromString("7fffffff-0000-0000-0000-000000000000");
    private static final UUID HIGH = UUID.fromString("80000000-0000-0000-0000-000000000000");

    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final CategoryInventoryRepository inventory = mock(CategoryInventoryRepository.class);
    private final EventServiceClient eventClient = mock(EventServiceClient.class);
    private final SeatServiceClient seatClient = mock(SeatServiceClient.class);
    private final RecordingTransactionManager txManager = new RecordingTransactionManager();

    @Test
    void customerCancel_locksTheBooking_thenReleasesInAscendingOrder() {
        Booking booking = booking(Booking.BookingStatus.PENDING);
        when(bookingRepo.lockById(booking.getId())).thenReturn(Optional.of(booking));

        bookingService().cancelBooking(booking.getId(), "u@example.com");

        InOrder order = inOrder(bookingRepo, inventory);
        order.verify(bookingRepo).lockById(booking.getId());
        order.verify(inventory).release(LOW, 1);
        order.verify(inventory).release(HIGH, 2);
    }

    @Test
    void adminReverse_locksTheBookingInItsWritePhase_thenReleasesInAscendingOrder() {
        Booking booking = booking(Booking.BookingStatus.CONFIRMED);
        when(bookingRepo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepo.lockById(booking.getId())).thenReturn(Optional.of(booking));
        AvailabilityResponseDTO released = new AvailabilityResponseDTO();
        released.setAvailableTickets(10);
        when(eventClient.releaseAvailability(any(), anyInt(), any())).thenReturn(ApiResult.ok(released));

        bookingService().reverseConfirmedBooking(booking.getId(), "admin@innbucks.co.zw");

        InOrder order = inOrder(bookingRepo, inventory);
        order.verify(bookingRepo).findByIdWithItems(booking.getId()); // read-only phase, no stock
        order.verify(bookingRepo).lockById(booking.getId());
        order.verify(inventory).release(LOW, 1);
        order.verify(inventory).release(HIGH, 2);
    }

    @Test
    void expirySweep_locksTheBooking_thenReleasesInAscendingOrder() {
        Booking booking = booking(Booking.BookingStatus.PENDING);
        when(bookingRepo.findExpiryCandidates(any(), any()))
                .thenReturn(List.of(new ExpiryCandidate(booking.getId(), booking.getExpiresAt())));
        when(bookingRepo.lockByIdSkipLocked(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepo.markExpired(eq(booking.getId()), any())).thenReturn(1);

        new BookingExpirationService(bookingRepo, inventory, mock(ApplicationEventPublisher.class), txManager,
                new BookingMetrics(new SimpleMeterRegistry())).expirePending();

        InOrder order = inOrder(bookingRepo, inventory);
        order.verify(bookingRepo).lockByIdSkipLocked(booking.getId());
        order.verify(inventory).release(LOW, 1);
        order.verify(inventory).release(HIGH, 2);
        order.verify(bookingRepo).markExpired(eq(booking.getId()), any());
    }

    @Test
    void resurrect_locksTheBooking_thenClaimsInAscendingOrder() {
        Booking booking = booking(Booking.BookingStatus.CANCELLED);
        booking.setCancelReason(Booking.CancelReason.EXPIRED);
        when(bookingRepo.lockById(booking.getId())).thenReturn(Optional.of(booking));
        when(seatClient.getCategory(any())).thenAnswer(inv -> ApiResult.ok(CategoryLookupDTO.builder()
                .seatCategoryId(inv.getArgument(0)).name("Bay").totalSeats(10).build()));
        when(inventory.tryClaim(any(), anyInt())).thenReturn(1);

        bookingService().confirmBooking(booking.getId());

        InOrder order = inOrder(bookingRepo, inventory);
        // Phase 1 decides (no stock touched), phase 3 locks again for the claim.
        order.verify(bookingRepo, times(2)).lockById(booking.getId());
        order.verify(inventory).tryClaim(LOW, 1);
        order.verify(inventory).tryClaim(HIGH, 2);
    }

    @SuppressWarnings("unchecked")
    private BookingService bookingService() {
        ObjectProvider<EventServiceClient> events = mock(ObjectProvider.class);
        when(events.getIfAvailable()).thenReturn(eventClient);
        return new BookingService(bookingRepo, mock(BookingItemRepository.class), inventory,
                seatClient, mock(ApplicationEventPublisher.class), new QrCodeGenerator(),
                events, txManager);
    }

    private static Booking booking(Booking.BookingStatus status) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .version(1L)
                .userEmail("u@example.com")
                .eventId(UUID.randomUUID())
                .confirmationNumber("INN-LOCK")
                .status(status)
                .totalAmount(new BigDecimal("30.00"))
                .expiresAt(status == Booking.BookingStatus.PENDING
                        ? LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1) : null)
                .items(new ArrayList<>())
                .build();
        // Request order HIGH, LOW, HIGH: the release must still go LOW first.
        for (UUID category : List.of(HIGH, LOW, HIGH)) {
            booking.getItems().add(BookingItem.builder().booking(booking).categoryId(category)
                    .ticketNumber("T-" + UUID.randomUUID()).categoryName("Bay").build());
        }
        return booking;
    }
}
