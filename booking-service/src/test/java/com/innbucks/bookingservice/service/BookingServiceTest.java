package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailableSeatDTO;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CategoryBookingDTO;
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

    // Wires up a mocked SeatServiceClient that:
    //  - returns the given lookups by seatId for /seats/{id}/lookup, and
    //  - returns the same seat IDs as the available pool for the
    //    DEFAULT_CATEGORY_ID, so the random pick exhausts exactly the
    //    fixture's seats (and pickRandomAvailable can't loop).
    private SeatServiceClient stubClient(List<SeatLookupResponseDTO> lookups) {
        SeatServiceClient client = mock(SeatServiceClient.class);
        for (SeatLookupResponseDTO lookup : lookups) {
            when(client.lookupSeat(lookup.getSeatId()))
                    .thenReturn(ApiResult.ok("Seat details retrieved successfully", lookup));
        }
        List<AvailableSeatDTO> available = lookups.stream()
                .map(l -> AvailableSeatDTO.builder()
                        .id(l.getSeatId())
                        .categoryId(l.getCategoryId())
                        .categoryName(l.getCategoryName())
                        .sectionLabel(l.getSectionLabel())
                        .seatNumber(l.getSeatNumber())
                        .status("AVAILABLE")
                        .build())
                .toList();
        when(client.getAvailableSeats(DEFAULT_CATEGORY_ID))
                .thenReturn(ApiResult.ok("Available seats retrieved successfully", available));
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
        // Real QrCodeGenerator — fast (in-memory ZXing) and lets us assert
        // qrCode round-trips without mocking. Tests that don't care about
        // QRs simply ignore the field.
        // null loyalty/event providers — BookingService treats these as
        // "loyalty not wired" and just confirms cash-only bookings.
        return new BookingService(bookingRepo, itemRepo, seatClient, eventPublisher,
                new QrCodeGenerator(), null, null);
    }

    @Test
    void createBooking_rejectsWhenPickedSeatIsAlreadyInActiveBooking() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        // Single-seat pool ⇒ the random pick is deterministic.
        RequestFixture fx = request(new BigDecimal("20.00"));
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx.lookups));

        UUID clashingSeatId = fx.lookups.get(0).getSeatId();
        BookingItem existing = BookingItem.builder().seatId(clashingSeatId).build();
        when(itemRepo.findActiveBySeatIds(any(), eq(Booking.BookingStatus.CANCELLED)))
                .thenReturn(List.of(existing));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createBooking("user@example.com", 4, null, fx.request));
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
                () -> service.createBooking("u@example.com", 1, null, fx.request));
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
                () -> service.createBooking("u@example.com", 2, null, fx.request));
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

        BookingResponseDTO resp = service.createBooking("u@example.com", 3, null, fx.request);

        assertEquals(6, resp.getItems().size());
    }

    @Test
    void createBooking_totalsAllSeatPrices_andCreatesOneItemPerSeat() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"), new BigDecimal("15.50"), new BigDecimal("4.50"));
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx.lookups));

        BookingResponseDTO resp = service.createBooking("user@example.com", 4, null, fx.request);

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
    void createBooking_rejectsWhenCategoryBelongsToDifferentEvent() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"));
        // Seat-service says the picked seat's category belongs to a different event
        // than the one in the booking request.
        SeatLookupResponseDTO mismatched = fx.lookups.get(0);
        mismatched.setEventId(UUID.randomUUID());
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx.lookups));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createBooking("user@example.com", 4, null, fx.request));
        assertTrue(ex.getMessage().contains("does not belong to event"));
        verify(bookingRepo, never()).save(any());
        verify(itemRepo, never()).saveAll(any());
    }

    @Test
    void createBooking_rejectsWhenCategoryHasNoAvailableSeats() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"));
        // Override the available-seats stub: empty pool simulates a sold-out category.
        SeatServiceClient client = mock(SeatServiceClient.class);
        when(client.getAvailableSeats(DEFAULT_CATEGORY_ID))
                .thenReturn(ApiResult.ok("ok", List.of()));
        BookingService service = newService(bookingRepo, itemRepo, client);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createBooking("user@example.com", 4, null, fx.request));
        assertTrue(ex.getMessage().toLowerCase().contains("no available seats"));
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

        BookingResponseDTO resp = service.createBooking("user@example.com", 4, null, fx.request);

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

        BookingResponseDTO resp = service.createBooking("u@example.com", 4, null, fx.request);

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

        BookingResponseDTO resp = service.createBooking("u@example.com", 4, null, fx.request);

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

        service.createBooking("u@example.com", 4, null, fx.request);

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
    void createBooking_setsExpiresAtToHoldWindowFromNow() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("10.00"));
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx.lookups));

        java.time.LocalDateTime before = java.time.LocalDateTime.now();
        BookingResponseDTO resp = service.createBooking("u@example.com", 4, null, fx.request);
        java.time.LocalDateTime after = java.time.LocalDateTime.now();

        // Default holdTtlMinutes is 5 → expiresAt is roughly 5 min from now.
        assertNotNull(resp.getExpiresAt());
        assertTrue(resp.getExpiresAt().isAfter(before.plusMinutes(4).plusSeconds(50)));
        assertTrue(resp.getExpiresAt().isBefore(after.plusMinutes(5).plusSeconds(10)));
        assertEquals(Booking.BookingStatus.PENDING, resp.getStatus());

        // The Booking entity persisted to the repo also carries it.
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, atLeastOnce()).save(captor.capture());
        assertNotNull(captor.getValue().getExpiresAt());
    }

    @Test
    void confirmBooking_clearsExpiresAtOnceBookingIsPaid() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("u@example.com")
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN)
                .expiresAt(java.time.LocalDateTime.now().plusMinutes(3))
                .build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        BookingResponseDTO resp = service.confirmBooking(id);

        assertEquals(Booking.BookingStatus.CONFIRMED, resp.getStatus());
        assertNull(resp.getExpiresAt(), "expiresAt should be null on a paid booking");
        assertNull(booking.getExpiresAt());
    }

    @Test
    void confirmBooking_rejectsWhenHoldHasAlreadyExpired() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("u@example.com")
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN)
                .expiresAt(java.time.LocalDateTime.now().minusSeconds(1)) // already past
                .build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.confirmBooking(id));
        assertTrue(ex.getMessage().toLowerCase().contains("expired"));
        // Booking stays PENDING — the expiration scheduler will pick it up.
        assertEquals(Booking.BookingStatus.PENDING, booking.getStatus());
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void cancelBooking_clearsExpiresAtSoExpirationSchedulerSkipsIt() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("u@example.com")
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN)
                .expiresAt(java.time.LocalDateTime.now().plusMinutes(3))
                .build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        service.cancelBooking(id, "u@example.com");

        assertEquals(Booking.BookingStatus.CANCELLED, booking.getStatus());
        assertNull(booking.getExpiresAt());
    }

    @Test
    void createBooking_stampsPhoneNumberFromJwtOntoBookingAndResponse() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(BigDecimal.TEN);
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx.lookups));

        BookingResponseDTO resp = service.createBooking(
                "u@example.com", 4, "+254700000000", fx.request);

        assertEquals("+254700000000", resp.getPhoneNumber(),
                "phoneNumber from JWT should land on the response");
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, atLeastOnce()).save(captor.capture());
        assertEquals("+254700000000", captor.getValue().getPhoneNumber(),
                "phoneNumber should be stored on the Booking entity");
    }

    @Test
    void createBooking_handlesMissingPhoneNumberClaimGracefully() {
        // System users / older JWTs don't carry the claim — booking still works.
        RequestFixture fx = request(BigDecimal.TEN);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx.lookups));

        BookingResponseDTO resp = service.createBooking("u@example.com", 4, null, fx.request);

        assertNull(resp.getPhoneNumber());
        assertEquals(Booking.BookingStatus.PENDING, resp.getStatus());
    }

    @Test
    void getByPhoneNumber_returnsAllMatchingBookingsMostRecentFirst() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        Booking older = Booking.builder().id(UUID.randomUUID()).userEmail("u@example.com")
                .phoneNumber("+254700000000").confirmationNumber("INN-1")
                .status(Booking.BookingStatus.CONFIRMED).totalAmount(BigDecimal.TEN).build();
        Booking newer = Booking.builder().id(UUID.randomUUID()).userEmail("u@example.com")
                .phoneNumber("+254700000000").confirmationNumber("INN-2")
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN).build();
        // Repository orders most-recent-first by contract; assert that order
        // surfaces unchanged.
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc("+254700000000"))
                .thenReturn(List.of(newer, older));

        List<BookingResponseDTO> result = service.getByPhoneNumber("+254700000000");

        assertEquals(2, result.size());
        assertEquals("INN-2", result.get(0).getConfirmationNumber());
        assertEquals("INN-1", result.get(1).getConfirmationNumber());
        assertEquals("+254700000000", result.get(0).getPhoneNumber());
    }

    @Test
    void getByPhoneNumber_returnsEmptyListWhenNoMatch() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc("+999"))
                .thenReturn(List.of());

        List<BookingResponseDTO> result = service.getByPhoneNumber("+999");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void createBooking_attachesPerSeatQrCodeToEachItem() {
        RequestFixture fx = request(BigDecimal.TEN, BigDecimal.TEN);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx.lookups));

        BookingResponseDTO resp = service.createBooking("u@example.com", 4, null, fx.request);

        assertEquals(2, resp.getItems().size());
        for (var item : resp.getItems()) {
            assertNotNull(item.getQrCode(), "every item should have a QR code");
            assertTrue(item.getQrCode().startsWith("data:image/png;base64,"),
                    "QR should be a base64 PNG data URI; got: "
                            + item.getQrCode().substring(0, Math.min(40, item.getQrCode().length())));
        }
        // QR codes should be unique per ticket — they encode unique ticket numbers.
        long uniqueQrs = resp.getItems().stream().map(i -> i.getQrCode()).distinct().count();
        assertEquals(resp.getItems().size(), uniqueQrs, "each ticket gets its own QR");
    }

    @Test
    void getBookingsByCategory_returnsOneRowPerBookingItemFlattened() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        BookingService service = newService(bookingRepo, itemRepo, mock(SeatServiceClient.class));

        UUID categoryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID bookingId1 = UUID.randomUUID();
        UUID bookingId2 = UUID.randomUUID();
        Booking b1 = Booking.builder()
                .id(bookingId1).userEmail("alice@example.com").eventId(eventId)
                .confirmationNumber("INN-1").status(Booking.BookingStatus.CONFIRMED)
                .totalAmount(new BigDecimal("100.00")).build();
        Booking b2 = Booking.builder()
                .id(bookingId2).userEmail("bob@example.com").eventId(eventId)
                .confirmationNumber("INN-2").status(Booking.BookingStatus.CANCELLED)
                .totalAmount(new BigDecimal("50.00")).build();
        BookingItem item1 = BookingItem.builder()
                .booking(b1).seatId(UUID.randomUUID()).categoryId(categoryId)
                .categoryName("VIP").rowLabel("A").seatNumber(1).ticketNumber("T1")
                .priceAtBooking(new BigDecimal("100.00")).build();
        BookingItem item2 = BookingItem.builder()
                .booking(b2).seatId(UUID.randomUUID()).categoryId(categoryId)
                .categoryName("VIP").rowLabel("A").seatNumber(2).ticketNumber("T2")
                .priceAtBooking(new BigDecimal("50.00")).build();
        when(itemRepo.findByCategoryIdWithBooking(categoryId)).thenReturn(List.of(item1, item2));

        List<CategoryBookingDTO> result = service.getBookingsByCategory(categoryId, "admin@test", true);

        assertEquals(2, result.size());
        // Booking-level fields surface on every row.
        assertEquals("alice@example.com", result.get(0).getUserEmail());
        assertEquals(Booking.BookingStatus.CONFIRMED, result.get(0).getStatus());
        assertEquals("INN-1", result.get(0).getConfirmationNumber());
        // Seat-level fields surface too.
        assertEquals("T1", result.get(0).getTicketNumber());
        assertEquals(0, new BigDecimal("100.00").compareTo(result.get(0).getPriceAtBooking()));
        // Cancelled bookings are NOT filtered out — caller decides.
        assertEquals(Booking.BookingStatus.CANCELLED, result.get(1).getStatus());
    }

    @Test
    void getBookingsByCategory_returnsEmptyListWhenNoneFound() {
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        BookingService service = newService(mock(BookingRepository.class), itemRepo, mock(SeatServiceClient.class));
        UUID categoryId = UUID.randomUUID();
        when(itemRepo.findByCategoryIdWithBooking(categoryId)).thenReturn(List.of());

        List<CategoryBookingDTO> result = service.getBookingsByCategory(categoryId, "admin@test", true);

        assertNotNull(result);
        assertTrue(result.isEmpty());
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
