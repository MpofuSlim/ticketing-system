package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookingExpirationServiceTest {

    private Booking pendingBooking(LocalDateTime expiresAt) {
        return Booking.builder()
                .id(UUID.randomUUID())
                .userEmail("u@example.com")
                .eventId(UUID.randomUUID())
                .confirmationNumber("INN-X")
                .status(Booking.BookingStatus.PENDING)
                .totalAmount(BigDecimal.TEN)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    void expirePending_doesNothingWhenNoBookingsAreExpired() {
        BookingRepository repo = mock(BookingRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(repo.findExpiredPending(any())).thenReturn(List.of());

        new BookingExpirationService(repo, mock(CategoryInventoryRepository.class), publisher).expirePending();

        verify(repo, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void expirePending_flipsStatusToCancelledAndClearsExpiresAt() {
        BookingRepository repo = mock(BookingRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        Booking expired = pendingBooking(LocalDateTime.now().minusMinutes(1));
        when(repo.findExpiredPending(any())).thenReturn(List.of(expired));

        new BookingExpirationService(repo, mock(CategoryInventoryRepository.class), publisher).expirePending();

        assertEquals(Booking.BookingStatus.CANCELLED, expired.getStatus());
        assertNull(expired.getExpiresAt());
        verify(repo).save(expired);
    }

    @Test
    void expirePending_publishesBookingCancelledEventForEachExpiredBooking() {
        BookingRepository repo = mock(BookingRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        Booking a = pendingBooking(LocalDateTime.now().minusMinutes(1));
        Booking b = pendingBooking(LocalDateTime.now().minusMinutes(2));
        when(repo.findExpiredPending(any())).thenReturn(List.of(a, b));

        new BookingExpirationService(repo, mock(CategoryInventoryRepository.class), publisher).expirePending();

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(2)).publishEvent(events.capture());
        events.getAllValues().forEach(e ->
                assertInstanceOf(BookingDomainEvent.BookingCancelled.class, e));
    }

    @Test
    void expirePending_skipsBookingsWhoseStatusChangedBetweenQueryAndUpdate() {
        // Models the race where the payment endpoint flipped a booking to
        // CONFIRMED in between the query and our save. The job must be a
        // no-op for that booking — otherwise it would un-do the payment.
        BookingRepository repo = mock(BookingRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        Booking confirmedRaceWinner = pendingBooking(LocalDateTime.now().minusMinutes(1));
        confirmedRaceWinner.setStatus(Booking.BookingStatus.CONFIRMED); // changed under us
        when(repo.findExpiredPending(any())).thenReturn(List.of(confirmedRaceWinner));

        new BookingExpirationService(repo, mock(CategoryInventoryRepository.class), publisher).expirePending();

        // Status preserved, no save, no event.
        assertEquals(Booking.BookingStatus.CONFIRMED, confirmedRaceWinner.getStatus());
        verify(repo, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }
}
