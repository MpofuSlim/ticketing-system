package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.PublicBookingResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The buyer's link token (V31) reaches the buyer only, and the public view by
 * booking id closes behind {@link IdLinkPolicy} while the view by token never
 * does.
 */
class BookingLinkTokenServiceTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final BookingItemRepository itemRepo = mock(BookingItemRepository.class);

    private BookingService service(IdLinkPolicy policy) {
        BookingService service = new BookingService(bookingRepo, itemRepo, mock(CategoryInventoryRepository.class),
                mock(SeatServiceClient.class), mock(ApplicationEventPublisher.class), new QrCodeGenerator(), null,
                mock(PlatformTransactionManager.class));
        service.setIdLinkPolicy(policy);
        return service;
    }

    private static Booking booking(LocalDateTime createdAt) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .userEmail("alice@example.com")
                .phoneNumber("+263772000111")
                .confirmationNumber("INN-20261103-3C8849")
                .status(Booking.BookingStatus.CONFIRMED)
                .totalAmount(new BigDecimal("100.00"))
                .linkToken(TOKEN)
                .createdAt(createdAt)
                .build();
        booking.setItems(List.of(BookingItem.builder()
                .id(UUID.randomUUID())
                .booking(booking)
                .linkToken("fedcba9876543210fedcba9876543210")
                .categoryName("VIP")
                .priceAtBooking(new BigDecimal("100.00"))
                .ticketNumber("20261103-CH2F0-5V7CB")
                .build()));
        return booking;
    }

    // ---- who gets the token -----------------------------------------------

    @Test
    void theBuyersOwnList_carriesTheViewToken() {
        Booking booking = booking(LocalDateTime.of(2026, 11, 3, 8, 0));
        when(bookingRepo.findByUserEmail("alice@example.com")).thenReturn(List.of(booking));

        assertThat(service(IdLinkPolicy.OPEN).getMyBookings("alice@example.com"))
                .singleElement()
                .satisfies(dto -> assertThat(dto.getViewToken()).isEqualTo(TOKEN));
    }

    @Test
    void everyOtherRead_carriesNoToken_theBuyersByIdStaffAndServices() {
        Booking booking = booking(LocalDateTime.of(2026, 11, 3, 8, 0));
        when(bookingRepo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));
        BookingService service = service(IdLinkPolicy.OPEN);

        // Only the create response and GET /bookings/my carry it: a by-id read
        // is also how staff, cancel and reverse answers are built.
        assertThat(service.getBookingById(booking.getId(), "alice@example.com", false).getViewToken()).isNull();
        assertThat(service.getBookingById(booking.getId(), "admin@innbucks.co.zw", true).getViewToken()).isNull();
        assertThat(service.getBookingForService(booking.getId()).getViewToken()).isNull();
    }

    // ---- the public view ---------------------------------------------------

    @Test
    void theViewByToken_isTheSamePublicView_andNeverCarriesAToken() {
        Booking booking = booking(LocalDateTime.of(2026, 11, 3, 8, 0));
        when(bookingRepo.findByLinkTokenWithItems(TOKEN)).thenReturn(Optional.of(booking));

        PublicBookingResponseDTO view = service(IdLinkPolicy.OPEN).getBookingByTokenPublic(TOKEN);

        assertThat(view.getId()).isEqualTo(booking.getId());
        assertThat(view.getItems().get(0).getTicketNumber()).isEqualTo("20261103-CH2F0-5V7CB");
        assertThat(view.toString()).doesNotContain(TOKEN).doesNotContain("fedcba9876543210fedcba9876543210");
    }

    @Test
    void anUnknownOrMalformedToken_is404_andAMalformedOneIsNeverLookedUp() {
        String unknown = "ffffffffffffffffffffffffffffffff";
        when(bookingRepo.findByLinkTokenWithItems(unknown)).thenReturn(Optional.empty());
        BookingService service = service(IdLinkPolicy.OPEN);

        assertThatThrownBy(() -> service.getBookingByTokenPublic(unknown)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getBookingByTokenPublic("NOT-A-TOKEN")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getBookingByTokenPublic(null)).isInstanceOf(NotFoundException.class);
        verify(bookingRepo, never()).findByLinkTokenWithItems("NOT-A-TOKEN");
    }

    @Test
    void theViewById_closesBehindTheSwitch_exactlyLikeAnUnknownId_andTheTokenViewStaysOpen() {
        Booking booking = booking(LocalDateTime.of(2026, 11, 3, 8, 0));
        when(bookingRepo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepo.findByLinkTokenWithItems(TOKEN)).thenReturn(Optional.of(booking));

        assertThat(service(IdLinkPolicy.OPEN).getBookingByIdPublic(booking.getId()).getId())
                .isEqualTo(booking.getId());

        BookingService closed = service(new IdLinkPolicy(IdLinkPolicy.Mode.CLOSED, null, null));
        assertThatThrownBy(() -> closed.getBookingByIdPublic(booking.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Booking not found");
        assertThat(closed.getBookingByTokenPublic(TOKEN).getId()).isEqualTo(booking.getId());
    }

    @Test
    void legacy_keepsTheViewById_forBookingsMadeBeforeTheCutOver_only() {
        IdLinkPolicy legacy = new IdLinkPolicy(IdLinkPolicy.Mode.LEGACY, LocalDateTime.of(2026, 11, 10, 8, 0), null);
        Booking old = booking(LocalDateTime.of(2026, 11, 9, 23, 0));
        Booking fresh = booking(LocalDateTime.of(2026, 11, 10, 9, 0));
        when(bookingRepo.findByIdWithItems(old.getId())).thenReturn(Optional.of(old));
        when(bookingRepo.findByIdWithItems(fresh.getId())).thenReturn(Optional.of(fresh));
        BookingService service = service(legacy);

        assertThat(service.getBookingByIdPublic(old.getId()).getId()).isEqualTo(old.getId());
        assertThatThrownBy(() -> service.getBookingByIdPublic(fresh.getId())).isInstanceOf(NotFoundException.class);
        verify(bookingRepo, never()).findByLinkTokenWithItems(anyString());
    }
}
