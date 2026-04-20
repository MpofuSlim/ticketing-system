package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private CreateBookingRequestDTO.SeatItemRequest seatItem(BigDecimal price) {
        CreateBookingRequestDTO.SeatItemRequest s = new CreateBookingRequestDTO.SeatItemRequest();
        s.setSeatId(UUID.randomUUID());
        s.setCategoryId(UUID.randomUUID());
        s.setRowLabel("A");
        s.setSeatNumber(1);
        s.setCategoryName("VIP");
        s.setPriceAtBooking(price);
        return s;
    }

    private CreateBookingRequestDTO request(BigDecimal... prices) {
        CreateBookingRequestDTO req = new CreateBookingRequestDTO();
        req.setEventId(UUID.randomUUID());
        req.setSeats(java.util.Arrays.stream(prices).map(this::seatItem).toList());
        return req;
    }

    @Test
    void createBooking_totalsAllSeatPrices_andCreatesOneItemPerSeat() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        BookingService service = new BookingService(bookingRepo, itemRepo);

        BookingResponseDTO resp = service.createBooking("user@example.com",
                request(new BigDecimal("20.00"), new BigDecimal("15.50"), new BigDecimal("4.50")));

        ArgumentCaptor<Booking> savedBooking = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, atLeastOnce()).save(savedBooking.capture());
        Booking booking = savedBooking.getValue();
        assertEquals(0, new BigDecimal("40.00").compareTo(booking.getTotalAmount()));
        assertEquals("user@example.com", booking.getUserEmail());
        assertEquals(Booking.BookingStatus.PENDING, booking.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BookingItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(itemRepo).saveAll(itemsCaptor.capture());
        assertEquals(3, itemsCaptor.getValue().size());
        assertEquals(3, resp.getItems().size());
    }

    @Test
    void createBooking_generatesUniqueTicketNumbersForEachSeat() {
        BookingService service = new BookingService(mock(BookingRepository.class), mock(BookingItemRepository.class));

        BookingResponseDTO resp = service.createBooking("u@example.com",
                request(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));

        long unique = resp.getItems().stream().map(i -> i.getTicketNumber()).distinct().count();
        assertEquals(resp.getItems().size(), unique, "ticket numbers should be unique");

        // Format: YYYYMMDD-DDDDDL
        Pattern ticketFormat = Pattern.compile("\\d{8}-\\d{5}[A-Z]");
        assertTrue(resp.getItems().stream().allMatch(i -> ticketFormat.matcher(i.getTicketNumber()).matches()));
    }

    @Test
    void createBooking_producesConfirmationNumberWithExpectedFormat() {
        BookingService service = new BookingService(mock(BookingRepository.class), mock(BookingItemRepository.class));

        BookingResponseDTO resp = service.createBooking("u@example.com", request(BigDecimal.TEN));

        // Format: INN-YYYYMMDD-XXXXXX (6 hex chars upper)
        assertTrue(Pattern.matches("INN-\\d{8}-[A-F0-9]{6}", resp.getConfirmationNumber()),
                "unexpected confirmation number: " + resp.getConfirmationNumber());
    }

    @Test
    void getBookingById_rejectsWhenAccessedByDifferentUser() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = new BookingService(bookingRepo, mock(BookingItemRepository.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("owner@example.com")
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getBookingById(id, "intruder@example.com"));
        assertEquals("Access denied", ex.getMessage());
    }

    @Test
    void getBookingById_throwsWhenMissing() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        when(bookingRepo.findById(any())).thenReturn(Optional.empty());
        BookingService service = new BookingService(bookingRepo, mock(BookingItemRepository.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getBookingById(UUID.randomUUID(), "u@example.com"));
        assertEquals("Booking not found", ex.getMessage());
    }

    @Test
    void cancelBooking_byOwner_transitionsPendingToCancelled() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = new BookingService(bookingRepo, mock(BookingItemRepository.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("owner@example.com")
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        service.cancelBooking(id, "owner@example.com");

        assertEquals(Booking.BookingStatus.CANCELLED, booking.getStatus());
        verify(bookingRepo).save(booking);
    }

    @Test
    void cancelBooking_rejectsConfirmedBookings() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = new BookingService(bookingRepo, mock(BookingItemRepository.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("owner@example.com")
                .status(Booking.BookingStatus.CONFIRMED).totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.cancelBooking(id, "owner@example.com"));
        assertTrue(ex.getMessage().contains("confirmed"));
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void cancelBooking_rejectsAlreadyCancelledBookings() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = new BookingService(bookingRepo, mock(BookingItemRepository.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("owner@example.com")
                .status(Booking.BookingStatus.CANCELLED).totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.cancelBooking(id, "owner@example.com"));
        assertTrue(ex.getMessage().contains("already cancelled"));
    }

    @Test
    void cancelBooking_rejectsWhenNotOwner() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = new BookingService(bookingRepo, mock(BookingItemRepository.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("owner@example.com")
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.cancelBooking(id, "other@example.com"));
        assertEquals("Access denied", ex.getMessage());
    }

    @Test
    void confirmBooking_transitionsPendingToConfirmed() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = new BookingService(bookingRepo, mock(BookingItemRepository.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("owner@example.com")
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        service.confirmBooking(id);

        assertEquals(Booking.BookingStatus.CONFIRMED, booking.getStatus());
        verify(bookingRepo).save(booking);
    }

    @Test
    void confirmBooking_rejectsNonPendingBookings() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = new BookingService(bookingRepo, mock(BookingItemRepository.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).status(Booking.BookingStatus.CONFIRMED)
                .userEmail("u@example.com").totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.confirmBooking(id));
        assertTrue(ex.getMessage().contains("pending"));
        verify(bookingRepo, never()).save(any());
    }
}
