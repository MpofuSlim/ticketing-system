package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.exception.BookingConflictException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.loyalty.LoyaltyEarnRetryService;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins the hold-extension rules payment-service relies on before minting an
 * InnBucks code: only a LIVE, PENDING hold extends; the extension never
 * shortens; everything else refuses so the payment is refused BEFORE money
 * moves (the paid-but-no-ticket gap).
 */
@SuppressWarnings("unchecked")
class BookingServiceExtendHoldTest {

    private BookingRepository bookingRepository;
    private BookingService service;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        service = new BookingService(
                bookingRepository,
                mock(BookingItemRepository.class),
                mock(CategoryInventoryRepository.class),
                mock(SeatServiceClient.class),
                mock(ApplicationEventPublisher.class),
                mock(QrCodeGenerator.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(LoyaltyEarnRetryService.class),
                mock(PlatformTransactionManager.class));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Booking pendingBooking(LocalDateTime expiresAt) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setStatus(Booking.BookingStatus.PENDING);
        b.setExpiresAt(expiresAt);
        return b;
    }

    @Test
    void livePendingHold_isExtendedToTheRequestedDeadline() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Booking b = pendingBooking(now.plusMinutes(3));
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));
        LocalDateTime until = now.plusMinutes(13);

        BookingResponseDTO dto = service.extendHold(b.getId(), until);

        assertThat(dto.getExpiresAt()).isEqualTo(until);
        verify(bookingRepository).save(b);
    }

    @Test
    void extension_neverShortensALongerHold() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime alreadyLong = now.plusMinutes(30);
        Booking b = pendingBooking(alreadyLong);
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        BookingResponseDTO dto = service.extendHold(b.getId(), now.plusMinutes(13));

        assertThat(dto.getExpiresAt()).isEqualTo(alreadyLong);
        // No-op extension — nothing written.
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void lapsedHold_refuses409_paymentMustNotProceed() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Booking b = pendingBooking(now.minusSeconds(30));
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.extendHold(b.getId(), now.plusMinutes(13)))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("expired");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void nonPendingBooking_refuses() {
        Booking b = pendingBooking(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(3));
        b.setStatus(Booking.BookingStatus.CONFIRMED);
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.extendHold(b.getId(),
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(13)))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void unknownBooking_isNotFound() {
        UUID id = UUID.randomUUID();
        when(bookingRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.extendHold(id,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(13)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void nullDeadline_isRejectedOutright() {
        assertThatThrownBy(() -> service.extendHold(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
