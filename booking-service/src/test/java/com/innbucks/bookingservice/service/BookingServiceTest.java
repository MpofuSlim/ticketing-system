package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
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
        // "loyalty not wired" and just confirms cash-only bookings. The
        // applyLoyalty short-circuit means LoyaltyEarnRetryService is
        // never invoked, so null is safe here.
        return new BookingService(bookingRepo, itemRepo, seatClient, eventPublisher,
                new QrCodeGenerator(), null, null, null);
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
    void createBooking_ticketNumbersVaryAcrossBookings_notDrawnFromAPredictableSequence() {
        // Security regression guard for the SecureRandom swap. A ticket number
        // is the QR-code payload validated at the gate, so it must be
        // unpredictable. We can't assert "is a CSPRNG" directly, but we CAN
        // assert the variable part (5 digits + 1 letter) spreads widely across
        // many generations — a regression to a constant, a fixed seed, or a
        // tiny cycle would collapse that spread and fail here.
        java.util.Set<String> variablePart = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            RequestFixture fx = request(BigDecimal.ONE);
            BookingService perCall = newService(mock(BookingRepository.class),
                    mock(BookingItemRepository.class), stubClient(fx.lookups));
            String ticket = perCall.createBooking("u@example.com", 4, null, fx.request)
                    .getItems().get(0).getTicketNumber();
            // Strip the leading "YYYYMMDD-" date prefix; keep the random tail.
            variablePart.add(ticket.substring(ticket.indexOf('-') + 1));
        }
        // 200 draws from a 2.6M space (10^5 * 26): collisions are vanishingly
        // unlikely with a real CSPRNG. Anything under ~190 distinct values
        // signals the randomness collapsed (constant, fixed seed, or tiny cycle).
        assertTrue(variablePart.size() >= 190,
                "ticket randomness collapsed: only " + variablePart.size() + " distinct of 200");
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
    void confirmBooking_alreadyConfirmed_isIdempotentReplay() {
        // Was: this test asserted that re-confirming a CONFIRMED booking throws.
        // Audit hardening: confirm is now idempotent for CONFIRMED — payment-service
        // retries (or replicas that lost their in-memory idempotency cache) must
        // see the same response, not a false 409 right after a successful confirm.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).status(Booking.BookingStatus.CONFIRMED)
                .userEmail("u@example.com").totalAmount(BigDecimal.TEN)
                .confirmationNumber("INN-20260602-AB12CD")
                .items(new ArrayList<>())
                .build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        BookingResponseDTO resp = service.confirmBooking(id);

        assertEquals(id, resp.getId());
        assertEquals(Booking.BookingStatus.CONFIRMED, resp.getStatus());
        // No state mutation — replay returns the existing row, doesn't re-save.
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void confirmBooking_cancelledBooking_stillRejects() {
        // CANCELLED is still a real error — it must NOT idempotent-replay.
        // A CANCELLED booking can't be confirmed; that's a client bug, not a retry.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).status(Booking.BookingStatus.CANCELLED)
                .userEmail("u@example.com").totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.confirmBooking(id));
        assertTrue(ex.getMessage().toLowerCase().contains("pending"));
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

    // ---- reverseConfirmedBooking (audit #3 — saga compensation) ----

    /** Build a BookingService with a stubbed EventServiceClient so the release path is reachable. */
    @SuppressWarnings("unchecked")
    private BookingService newServiceWithEventClient(BookingRepository bookingRepo,
                                                     ApplicationEventPublisher publisher,
                                                     EventServiceClient eventClient) {
        org.springframework.beans.factory.ObjectProvider<EventServiceClient> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(eventClient);
        return new BookingService(bookingRepo, mock(BookingItemRepository.class),
                mock(SeatServiceClient.class), publisher,
                new QrCodeGenerator(), null, provider, null);
    }

    private static AvailabilityResponseDTO availabilityResponse(int remaining) {
        AvailabilityResponseDTO dto = new AvailabilityResponseDTO();
        dto.setAvailableTickets(remaining);
        return dto;
    }

    private static Booking confirmedBookingWith(int itemCount) {
        Booking b = Booking.builder()
                .id(UUID.randomUUID())
                .userEmail("alice@example.com")
                .eventId(UUID.randomUUID())
                .status(Booking.BookingStatus.CONFIRMED)
                .totalAmount(BigDecimal.TEN)
                .availabilityReleased(false)
                .items(new ArrayList<>())
                .build();
        for (int i = 0; i < itemCount; i++) {
            b.getItems().add(BookingItem.builder().seatId(UUID.randomUUID()).build());
        }
        return b;
    }

    @Test
    void reverseBooking_restoresAvailability_flipsToCancelled_andSetsReleasedFlag() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        // event-service responds with a populated payload — release succeeded.
        when(eventClient.releaseAvailability(any(), eq(3), any())).thenReturn(
                ApiResult.ok("Availability released", availabilityResponse(50)));

        BookingService service = newServiceWithEventClient(bookingRepo, publisher, eventClient);
        Booking booking = confirmedBookingWith(3); // 3 items → release 3
        when(bookingRepo.findById(booking.getId())).thenReturn(Optional.of(booking));

        service.reverseConfirmedBooking(booking.getId(), "admin@example.com");

        verify(eventClient).releaseAvailability(eq(booking.getEventId()), eq(3), any());
        assertTrue(booking.isAvailabilityReleased(), "availability_released must be set after success");
        assertEquals(Booking.BookingStatus.CANCELLED, booking.getStatus());
        verify(bookingRepo).save(booking);

        ArgumentCaptor<Object> evCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(evCaptor.capture());
        assertInstanceOf(BookingDomainEvent.BookingCancelled.class, evCaptor.getValue());
    }

    @Test
    void reverseBooking_isIdempotent_whenAvailabilityAlreadyReleased() {
        // Partial-failure state from a previous attempt: release succeeded but the
        // local CANCELLED save failed. Retry must skip the release call to avoid
        // double-credit, but must still complete the status flip.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        BookingService service = newServiceWithEventClient(bookingRepo,
                mock(ApplicationEventPublisher.class), eventClient);

        Booking booking = confirmedBookingWith(2);
        booking.setAvailabilityReleased(true); // <- already released, but status still CONFIRMED
        when(bookingRepo.findById(booking.getId())).thenReturn(Optional.of(booking));

        service.reverseConfirmedBooking(booking.getId(), "admin@example.com");

        verifyNoInteractions(eventClient); // critical: no second release
        assertEquals(Booking.BookingStatus.CANCELLED, booking.getStatus());
        verify(bookingRepo).save(booking);
    }

    @Test
    void reverseBooking_failsAndLeavesBookingConfirmed_whenReleaseHttpFails() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        when(eventClient.releaseAvailability(any(), anyInt(), any()))
                .thenThrow(new RuntimeException("event-service down"));

        BookingService service = newServiceWithEventClient(bookingRepo,
                mock(ApplicationEventPublisher.class), eventClient);
        Booking booking = confirmedBookingWith(2);
        when(bookingRepo.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThrows(RuntimeException.class,
                () -> service.reverseConfirmedBooking(booking.getId(), "admin@example.com"));

        // No partial state: still CONFIRMED, flag still false → admin can retry safely.
        assertEquals(Booking.BookingStatus.CONFIRMED, booking.getStatus());
        assertFalse(booking.isAvailabilityReleased());
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void reverseBooking_failsWhenFallbackReturnsNullPayload() {
        // EventServiceClientFallback returns an ApiResult whose data is null when
        // the circuit is open. Treat that as a release failure — don't flip the
        // idempotency flag, don't mark CANCELLED.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        when(eventClient.releaseAvailability(any(), anyInt(), any())).thenReturn(
                ApiResult.<AvailabilityResponseDTO>builder()
                        .code("503").message("event unavailable").data(null).build());

        BookingService service = newServiceWithEventClient(bookingRepo,
                mock(ApplicationEventPublisher.class), eventClient);
        Booking booking = confirmedBookingWith(2);
        when(bookingRepo.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThrows(RuntimeException.class,
                () -> service.reverseConfirmedBooking(booking.getId(), "admin@example.com"));

        assertEquals(Booking.BookingStatus.CONFIRMED, booking.getStatus());
        assertFalse(booking.isAvailabilityReleased());
    }

    @Test
    void reverseBooking_rejectsPendingBooking() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newServiceWithEventClient(bookingRepo,
                mock(ApplicationEventPublisher.class), mock(EventServiceClient.class));

        Booking pending = confirmedBookingWith(1);
        pending.setStatus(Booking.BookingStatus.PENDING);
        when(bookingRepo.findById(pending.getId())).thenReturn(Optional.of(pending));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.reverseConfirmedBooking(pending.getId(), "admin@example.com"));
        assertTrue(ex.getMessage().contains("CONFIRMED"));
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void reverseBooking_rejectsCancelledBooking() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newServiceWithEventClient(bookingRepo,
                mock(ApplicationEventPublisher.class), mock(EventServiceClient.class));

        Booking cancelled = confirmedBookingWith(1);
        cancelled.setStatus(Booking.BookingStatus.CANCELLED);
        when(bookingRepo.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.reverseConfirmedBooking(cancelled.getId(), "admin@example.com"));
        assertTrue(ex.getMessage().contains("CONFIRMED"));
    }

    @Test
    void reverseBooking_rejectsMissingBooking() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        when(bookingRepo.findById(any())).thenReturn(Optional.empty());
        BookingService service = newServiceWithEventClient(bookingRepo,
                mock(ApplicationEventPublisher.class), mock(EventServiceClient.class));

        assertThrows(RuntimeException.class,
                () -> service.reverseConfirmedBooking(UUID.randomUUID(), "admin@example.com"));
    }

    @Test
    void getActiveItemCountsByEvents_excludesCancelled_butIncludesPendingAndConfirmed() {
        // event-service's availableTickets safety net is driven by this query.
        // It MUST count PENDING + CONFIRMED (anything not CANCELLED) so the
        // public event card stays in sync with seat-service's per-category
        // counter — which drops the instant a booking goes PENDING (the seat
        // is LOCKED at that moment). Pinned by passing CANCELLED as the
        // EXCLUDED status, not CONFIRMED as the included one.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        BookingService service = newService(bookingRepo, itemRepo, mock(SeatServiceClient.class));

        UUID eventId = UUID.randomUUID();
        BookingItemRepository.EventActiveItemCount row = mock(BookingItemRepository.EventActiveItemCount.class);
        when(row.getEventId()).thenReturn(eventId);
        when(row.getCount()).thenReturn(3L);
        when(itemRepo.countActiveItemsByEventIds(eq(List.of(eventId)), eq(Booking.BookingStatus.CANCELLED)))
                .thenReturn(List.of(row));

        var result = service.getActiveItemCountsByEvents(List.of(eventId));
        assertEquals(1, result.size());
        assertEquals(eventId, result.get(0).getEventId());
        assertEquals(3L, result.get(0).getCount());

        // Belt-and-braces: prove the WRONG arg shape (filtering only CONFIRMED
        // in) isn't being passed. A drift back to b.status = CONFIRMED would
        // call the repository with the CONFIRMED status as the parameter, and
        // this verifies the CANCELLED status was used instead.
        verify(itemRepo).countActiveItemsByEventIds(eq(List.of(eventId)), eq(Booking.BookingStatus.CANCELLED));
        verify(itemRepo, never()).countActiveItemsByEventIds(any(), eq(Booking.BookingStatus.CONFIRMED));
        verify(itemRepo, never()).countActiveItemsByEventIds(any(), eq(Booking.BookingStatus.PENDING));
    }

    @Test
    void getActiveItemCountsByEvents_emptyOrNullInput_shortCircuits_noRepoCall() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        BookingService service = newService(bookingRepo, itemRepo, mock(SeatServiceClient.class));

        assertTrue(service.getActiveItemCountsByEvents(List.of()).isEmpty());
        assertTrue(service.getActiveItemCountsByEvents(null).isEmpty());
        verify(itemRepo, never()).countActiveItemsByEventIds(any(), any());
    }
}
