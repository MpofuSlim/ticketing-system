package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.dto.SeatLookupResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.exception.TierRequirementException;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private static final UUID DEFAULT_CATEGORY_ID = UUID.randomUUID();

    /** Holds a request plus the matching seat lookup stubs so we can wire them into a mock client. */
    private static final class RequestFixture {
        final CreateBookingRequestDTO request;
        final List<SeatLookupResponseDTO> lookups;
        RequestFixture(CreateBookingRequestDTO request, List<SeatLookupResponseDTO> lookups) {
            this.request = request;
            this.lookups = lookups;
        }
    }

    private RequestFixture request(BigDecimal... prices) {
        UUID eventId = UUID.randomUUID();
        CreateBookingRequestDTO req = new CreateBookingRequestDTO();
        req.setEventId(eventId);

        List<CreateBookingRequestDTO.SeatItemRequest> seats = new ArrayList<>();
        List<SeatLookupResponseDTO> lookups = new ArrayList<>();
        int idx = 1;
        for (BigDecimal price : prices) {
            UUID seatId = UUID.randomUUID();
            CreateBookingRequestDTO.SeatItemRequest s = new CreateBookingRequestDTO.SeatItemRequest();
            s.setSeatId(seatId);
            s.setCategoryId(DEFAULT_CATEGORY_ID);
            seats.add(s);

            lookups.add(SeatLookupResponseDTO.builder()
                    .seatId(seatId)
                    .eventId(eventId)
                    .categoryId(DEFAULT_CATEGORY_ID)
                    .categoryName("VIP")
                    .sectionLabel("A")
                    .seatNumber(idx++)
                    .price(price)
                    .status(SeatLookupResponseDTO.SeatStatus.LOCKED)
                    .build());
        }
        req.setSeats(seats);
        return new RequestFixture(req, lookups);
    }

    private SeatServiceClient stubClient(List<SeatLookupResponseDTO> lookups) {
        SeatServiceClient client = mock(SeatServiceClient.class);
        for (SeatLookupResponseDTO lookup : lookups) {
            when(client.lookupSeat(lookup.getSeatId()))
                    .thenReturn(ApiResult.ok("Seat details retrieved successfully", lookup));
        }
        return client;
    }

    private BookingService newService(BookingRepository bookingRepo,
                                       BookingItemRepository itemRepo,
                                       SeatServiceClient seatClient) {
        return newService(bookingRepo, itemRepo, seatClient, mock(ApplicationEventPublisher.class));
    }

    private BookingService newService(BookingRepository bookingRepo,
                                       BookingItemRepository itemRepo,
                                       SeatServiceClient seatClient,
                                       ApplicationEventPublisher eventPublisher) {
        return new BookingService(bookingRepo, itemRepo, seatClient, eventPublisher);
    }

    @Test
    void createBooking_rejectsWhenAnyRequestedSeatIsAlreadyInActiveBooking() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"));
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx.lookups));

        UUID clashingSeatId = fx.request.getSeats().get(0).getSeatId();
        BookingItem existing = BookingItem.builder().seatId(clashingSeatId).build();
        when(itemRepo.findActiveBySeatIds(any(), eq(Booking.BookingStatus.CANCELLED)))
                .thenReturn(List.of(existing));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createBooking("user@example.com", 4, fx.request));
        assertTrue(ex.getMessage().toLowerCase().contains("already booked"));
        verify(bookingRepo, never()).save(any());
        verify(itemRepo, never()).saveAll(any());
    }

    @Test
    void createBooking_rejectsTier1CustomersOutright() {
        RequestFixture fx = request(BigDecimal.TEN);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx.lookups));

        TierRequirementException ex = assertThrows(TierRequirementException.class,
                () -> service.createBooking("u@example.com", 1, fx.request));
        assertTrue(ex.getMessage().toLowerCase().contains("tier"));
        assertEquals(1, ex.getCurrentTier());
        assertEquals(2, ex.getRequiredTier()); // 1 seat fits tier 2's cap of 2
    }

    @Test
    void createBooking_rejectsWhenTier2ExceedsTwoSeats() {
        RequestFixture fx = request(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx.lookups));

        TierRequirementException ex = assertThrows(TierRequirementException.class,
                () -> service.createBooking("u@example.com", 2, fx.request));
        assertTrue(ex.getMessage().contains("2 seats"));
        assertEquals(2, ex.getCurrentTier());
        assertEquals(3, ex.getRequiredTier()); // 3 seats need tier 3 (cap 6)
    }

    @Test
    void createBooking_allowsTier3UpToSixSeats() {
        RequestFixture fx = request(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx.lookups));

        BookingResponseDTO resp = service.createBooking("u@example.com", 3, fx.request);

        assertEquals(6, resp.getItems().size());
    }

    @Test
    void createBooking_totalsAllSeatPrices_andCreatesOneItemPerSeat() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"), new BigDecimal("15.50"), new BigDecimal("4.50"));
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx.lookups));

        BookingResponseDTO resp = service.createBooking("user@example.com", 4, fx.request);

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
    void createBooking_rejectsWhenSeatBelongsToDifferentEvent() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"));
        // Override the lookup to point at a different event
        SeatLookupResponseDTO mismatched = fx.lookups.get(0);
        mismatched.setEventId(UUID.randomUUID());
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx.lookups));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createBooking("user@example.com", 4, fx.request));
        assertTrue(ex.getMessage().contains("does not belong to event"));
        verify(bookingRepo, never()).save(any());
        verify(itemRepo, never()).saveAll(any());
    }

    @Test
    void createBooking_rejectsWhenSeatBelongsToDifferentCategory() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"));
        // Seat-service says the seat is in a different category than the client claims
        fx.lookups.get(0).setCategoryId(UUID.randomUUID());
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx.lookups));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createBooking("user@example.com", 4, fx.request));
        assertTrue(ex.getMessage().contains("does not belong to category"));
        verify(bookingRepo, never()).save(any());
        verify(itemRepo, never()).saveAll(any());
    }

    @Test
    void createBooking_derivesPriceAndCategoryFromSeatService() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("99.99"));
        fx.lookups.get(0).setCategoryName("Diamond");
        fx.lookups.get(0).setSectionLabel("Z");
        fx.lookups.get(0).setSeatNumber(42);
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx.lookups));

        BookingResponseDTO resp = service.createBooking("user@example.com", 4, fx.request);

        assertEquals(0, new BigDecimal("99.99").compareTo(resp.getTotalAmount()));
        assertEquals("Diamond", resp.getItems().get(0).getCategoryName());
        assertEquals("Z", resp.getItems().get(0).getRowLabel());
        assertEquals(42, resp.getItems().get(0).getSeatNumber());
        assertEquals(0, new BigDecimal("99.99").compareTo(resp.getItems().get(0).getPriceAtBooking()));
    }

    @Test
    void createBooking_generatesUniqueTicketNumbersForEachSeat() {
        RequestFixture fx = request(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx.lookups));

        BookingResponseDTO resp = service.createBooking("u@example.com", 4, fx.request);

        long unique = resp.getItems().stream().map(i -> i.getTicketNumber()).distinct().count();
        assertEquals(resp.getItems().size(), unique, "ticket numbers should be unique");

        // Format: YYYYMMDD-DDDDDL
        Pattern ticketFormat = Pattern.compile("\\d{8}-\\d{5}[A-Z]");
        assertTrue(resp.getItems().stream().allMatch(i -> ticketFormat.matcher(i.getTicketNumber()).matches()));
    }

    @Test
    void createBooking_producesConfirmationNumberWithExpectedFormat() {
        RequestFixture fx = request(BigDecimal.TEN);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx.lookups));

        BookingResponseDTO resp = service.createBooking("u@example.com", 4, fx.request);

        // Format: INN-YYYYMMDD-XXXXXX (6 hex chars upper)
        assertTrue(Pattern.matches("INN-\\d{8}-[A-F0-9]{6}", resp.getConfirmationNumber()),
                "unexpected confirmation number: " + resp.getConfirmationNumber());
    }

    @Test
    void getBookingById_rejectsWhenAccessedByDifferentUser() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

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
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getBookingById(UUID.randomUUID(), "u@example.com"));
        assertEquals("Booking not found", ex.getMessage());
    }

    @Test
    void cancelBooking_byOwner_transitionsPendingToCancelled() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

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
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

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
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

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
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

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
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

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
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).status(Booking.BookingStatus.CONFIRMED)
                .userEmail("u@example.com").totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.confirmBooking(id));
        assertTrue(ex.getMessage().contains("pending"));
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void createBooking_publishesBookingCreatedEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        RequestFixture fx = request(BigDecimal.TEN);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx.lookups), publisher);

        service.createBooking("u@example.com", 4, fx.request);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertInstanceOf(BookingDomainEvent.BookingCreated.class, captor.getValue());
        BookingDomainEvent.BookingCreated created = (BookingDomainEvent.BookingCreated) captor.getValue();
        assertEquals("u@example.com", created.userEmail());
        assertEquals(0, BigDecimal.TEN.compareTo(created.totalAmount()));
        assertEquals(1, created.seatIds().size());
    }

    @Test
    void confirmBooking_publishesBookingConfirmedEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class), publisher);

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("u@example.com")
                .confirmationNumber("INN-X").status(Booking.BookingStatus.PENDING)
                .totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        service.confirmBooking(id);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertInstanceOf(BookingDomainEvent.BookingConfirmed.class, captor.getValue());
    }

    @Test
    void cancelBooking_publishesBookingCancelledEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class), publisher);

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("u@example.com")
                .confirmationNumber("INN-X").status(Booking.BookingStatus.PENDING)
                .totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        service.cancelBooking(id, "u@example.com");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertInstanceOf(BookingDomainEvent.BookingCancelled.class, captor.getValue());
    }
}
