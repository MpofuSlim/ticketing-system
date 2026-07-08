package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CategoryBookingDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.ConfirmBookingRequestDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.exception.TierRequirementException;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private static final UUID DEFAULT_CATEGORY_ID = UUID.randomUUID();

    private static final BigDecimal DEFAULT_PRICE = new BigDecimal("10.00");
    private static final int DEFAULT_TOTAL_SEATS = 1000;

    /**
     * Holds a request plus the category capacity/price it resolves to (GA
     * model). {@code prices.length} is the number of tickets requested; the
     * category's single price is {@code prices[0]} (in GA a category has one
     * price — multiple distinct prices in one category is not a thing).
     */
    private static final class RequestFixture {
        final CreateBookingRequestDTO request;
        final UUID eventId;
        final BigDecimal price;
        final int qty;
        RequestFixture(CreateBookingRequestDTO request, UUID eventId, BigDecimal price, int qty) {
            this.request = request;
            this.eventId = eventId;
            this.price = price;
            this.qty = qty;
        }
    }

    /** N seats all priced at 1.00 — for the per-tier seat-cap boundary tests. */
    private static BigDecimal[] nPrices(int n) {
        BigDecimal[] prices = new BigDecimal[n];
        java.util.Arrays.fill(prices, BigDecimal.ONE);
        return prices;
    }

    private RequestFixture request(BigDecimal... prices) {
        UUID eventId = UUID.randomUUID();
        CreateBookingRequestDTO req = new CreateBookingRequestDTO();
        req.setEventId(eventId);
        List<CreateBookingRequestDTO.SeatItemRequest> seats = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) {
            CreateBookingRequestDTO.SeatItemRequest s = new CreateBookingRequestDTO.SeatItemRequest();
            s.setCategoryId(DEFAULT_CATEGORY_ID);
            seats.add(s);
        }
        req.setSeats(seats);
        BigDecimal price = prices.length > 0 ? prices[0] : DEFAULT_PRICE;
        return new RequestFixture(req, eventId, price, prices.length);
    }

    // Mocked SeatServiceClient stubbing GET /seat-categories/{id} for the
    // fixture's category — the GA model resolves capacity + price + eventId by
    // category, not by picking a seat.
    private SeatServiceClient stubClient(RequestFixture fx) {
        SeatServiceClient client = mock(SeatServiceClient.class);
        CategoryLookupDTO category = CategoryLookupDTO.builder()
                .seatCategoryId(DEFAULT_CATEGORY_ID)
                .eventId(fx.eventId)
                .name("VIP")
                .price(fx.price)
                .totalSeats(DEFAULT_TOTAL_SEATS)
                .availableSeats(DEFAULT_TOTAL_SEATS)
                .build();
        when(client.getCategory(DEFAULT_CATEGORY_ID))
                .thenReturn(ApiResult.ok("Category retrieved successfully", category));
        return client;
    }

    /**
     * Default per-category inventory mock: every seed/claim/release succeeds
     * (tryClaim returns 1 = capacity available). Tests that need a sold-out
     * category pass a custom mock whose tryClaim returns 0.
     */
    private CategoryInventoryRepository successfulInventory() {
        CategoryInventoryRepository inv = mock(CategoryInventoryRepository.class);
        when(inv.seedIfAbsent(any(), anyInt())).thenReturn(1);
        when(inv.tryClaim(any(), anyInt())).thenReturn(1);
        when(inv.release(any(), anyInt())).thenReturn(1);
        return inv;
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
        return newService(bookingRepo, itemRepo, successfulInventory(), seatClient, eventPublisher);
    }

    private BookingService newService(BookingRepository bookingRepo,
                                       BookingItemRepository itemRepo,
                                       CategoryInventoryRepository inventoryRepo,
                                       SeatServiceClient seatClient,
                                       ApplicationEventPublisher eventPublisher) {
        // Real QrCodeGenerator — fast (in-memory ZXing) and lets us assert
        // qrCode round-trips without mocking. null loyalty/event providers —
        // BookingService treats these as "loyalty not wired" and just confirms
        // cash-only bookings.
        return new BookingService(bookingRepo, itemRepo, inventoryRepo, seatClient, eventPublisher,
                new QrCodeGenerator(), null, null, null,
                mock(PlatformTransactionManager.class));
    }

    @Test
    void publicView_withholdsTicketCredentialUntilConfirmed() {
        BookingRepository repo = mock(BookingRepository.class);
        BookingService service = newService(repo, mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        UUID id = java.util.UUID.randomUUID();
        BookingItem item = BookingItem.builder()
                .seatId(java.util.UUID.randomUUID())
                .categoryId(DEFAULT_CATEGORY_ID).categoryName("VIP")
                .priceAtBooking(new java.math.BigDecimal("50.00"))
                .ticketNumber("20260619-48291X")
                .build();

        // PENDING (unpaid) — the gate credential must be withheld.
        Booking pending = Booking.builder().id(id).eventId(java.util.UUID.randomUUID())
                .status(Booking.BookingStatus.PENDING).totalAmount(new java.math.BigDecimal("50.00"))
                .items(java.util.List.of(item)).build();
        when(repo.findById(id)).thenReturn(java.util.Optional.of(pending));

        var pendingView = service.getBookingByIdPublic(id);
        assertEquals(1, pendingView.getItems().size());
        assertNull(pendingView.getItems().get(0).getTicketNumber());
        assertNull(pendingView.getItems().get(0).getQrCode());

        // CONFIRMED (paid) — the credential is exposed so the ticket can render.
        Booking confirmed = Booking.builder().id(id).eventId(java.util.UUID.randomUUID())
                .status(Booking.BookingStatus.CONFIRMED).totalAmount(new java.math.BigDecimal("50.00"))
                .items(java.util.List.of(item)).build();
        when(repo.findById(id)).thenReturn(java.util.Optional.of(confirmed));

        var confirmedView = service.getBookingByIdPublic(id);
        assertEquals("20260619-48291X", confirmedView.getItems().get(0).getTicketNumber());
        assertNotNull(confirmedView.getItems().get(0).getQrCode());
    }

    @Test
    void createBooking_rejectsWhenCategorySoldOut() {
        // The per-category counter is the oversell guard: a claim that would
        // push remaining below zero returns 0 rows (tryClaim), which the service
        // turns into a 409 conflict. No booking row, no items.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"));

        CategoryInventoryRepository soldOut = mock(CategoryInventoryRepository.class);
        when(soldOut.seedIfAbsent(any(), anyInt())).thenReturn(0); // row already exists
        when(soldOut.tryClaim(any(), anyInt())).thenReturn(0);     // nothing left to claim
        BookingService service = newService(bookingRepo, itemRepo, soldOut,
                stubClient(fx), mock(ApplicationEventPublisher.class));

        com.innbucks.bookingservice.exception.BookingConflictException ex = assertThrows(
                com.innbucks.bookingservice.exception.BookingConflictException.class,
                () -> service.createBooking("user@example.com", 4, null, fx.request));
        assertTrue(ex.getMessage().toLowerCase().contains("not enough tickets"));
        verify(bookingRepo, never()).save(any());
        verify(itemRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void createBooking_rejectsTier1CustomersOutright() {
        RequestFixture fx = request(BigDecimal.TEN);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx));

        TierRequirementException ex = assertThrows(TierRequirementException.class,
                () -> service.createBooking("u@example.com", 1, null, fx.request));
        assertTrue(ex.getMessage().toLowerCase().contains("tier"));
        assertEquals(1, ex.getCurrentTier());
        assertEquals(2, ex.getRequiredTier()); // 1 seat fits tier 2's cap of 10
    }

    @Test
    void createBooking_allowsTier2UpToTenSeats() {
        // Tier 2's per-booking cap is 10 — exactly 10 must succeed.
        RequestFixture fx = request(nPrices(10));
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx));

        BookingResponseDTO resp = service.createBooking("u@example.com", 2, null, fx.request);

        assertEquals(10, resp.getItems().size());
    }

    @Test
    void createBooking_rejectsWhenTier2ExceedsTenSeats() {
        // 11 seats is over tier 2's cap of 10; 11 still fits tier 3 (cap 15),
        // so the required tier surfaced to the caller is 3.
        RequestFixture fx = request(nPrices(11));
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx));

        TierRequirementException ex = assertThrows(TierRequirementException.class,
                () -> service.createBooking("u@example.com", 2, null, fx.request));
        assertTrue(ex.getMessage().contains("10 seats"));
        assertEquals(2, ex.getCurrentTier());
        assertEquals(3, ex.getRequiredTier());
    }

    @Test
    void createBooking_allowsTier3UpToFifteenSeats() {
        // Tier 3's cap is now 15 — exactly 15 must succeed.
        RequestFixture fx = request(nPrices(15));
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx));

        BookingResponseDTO resp = service.createBooking("u@example.com", 3, null, fx.request);

        assertEquals(15, resp.getItems().size());
    }

    @Test
    void createBooking_totalsPricePerTicket_andCreatesOneItemPerTicket() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        // GA: one category, one price. 3 tickets at 20.00 = 60.00.
        RequestFixture fx = request(new BigDecimal("20.00"), new BigDecimal("20.00"), new BigDecimal("20.00"));
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx));

        BookingResponseDTO resp = service.createBooking("user@example.com", 4, null, fx.request);

        ArgumentCaptor<Booking> savedBooking = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, atLeastOnce()).save(savedBooking.capture());
        Booking booking = savedBooking.getValue();
        assertEquals(0, new BigDecimal("60.00").compareTo(booking.getTotalAmount()));
        assertEquals("user@example.com", booking.getUserEmail());
        assertEquals(Booking.BookingStatus.PENDING, booking.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BookingItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(itemRepo).saveAllAndFlush(itemsCaptor.capture());
        assertEquals(3, itemsCaptor.getValue().size());
        assertEquals(3, resp.getItems().size());
    }

    @Test
    void createBooking_rejectsWhenCategoryBelongsToDifferentEvent() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"));
        // seat-service says the category belongs to a different event than the
        // one in the booking request.
        SeatServiceClient client = mock(SeatServiceClient.class);
        CategoryLookupDTO mismatched = CategoryLookupDTO.builder()
                .seatCategoryId(DEFAULT_CATEGORY_ID)
                .eventId(UUID.randomUUID()) // != fx.eventId
                .name("VIP").price(fx.price)
                .totalSeats(DEFAULT_TOTAL_SEATS).availableSeats(DEFAULT_TOTAL_SEATS)
                .build();
        when(client.getCategory(DEFAULT_CATEGORY_ID))
                .thenReturn(ApiResult.ok("ok", mismatched));
        BookingService service = newService(bookingRepo, itemRepo, client);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createBooking("user@example.com", 4, null, fx.request));
        assertTrue(ex.getMessage().contains("does not belong to event"));
        verify(bookingRepo, never()).save(any());
        verify(itemRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void createBooking_derivesPriceAndCategoryFromSeatService() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("99.99"));
        SeatServiceClient client = mock(SeatServiceClient.class);
        CategoryLookupDTO category = CategoryLookupDTO.builder()
                .seatCategoryId(DEFAULT_CATEGORY_ID).eventId(fx.eventId)
                .name("Diamond").price(new BigDecimal("99.99"))
                .totalSeats(DEFAULT_TOTAL_SEATS).availableSeats(DEFAULT_TOTAL_SEATS)
                .build();
        when(client.getCategory(DEFAULT_CATEGORY_ID))
                .thenReturn(ApiResult.ok("ok", category));
        BookingService service = newService(bookingRepo, itemRepo, client);

        BookingResponseDTO resp = service.createBooking("user@example.com", 4, null, fx.request);

        assertEquals(0, new BigDecimal("99.99").compareTo(resp.getTotalAmount()));
        assertEquals("Diamond", resp.getItems().get(0).getCategoryName());
        assertEquals("GA", resp.getItems().get(0).getRowLabel());
        assertEquals(0, new BigDecimal("99.99").compareTo(resp.getItems().get(0).getPriceAtBooking()));
    }

    @Test
    void createBooking_generatesUniqueTicketNumbersForEachSeat() {
        RequestFixture fx = request(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx));

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
                    mock(BookingItemRepository.class), stubClient(fx));
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
                mock(BookingItemRepository.class), stubClient(fx));

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
    void getBookingById_missing_throwsTypedNotFound_so404NotBare500() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        when(bookingRepo.findById(any())).thenReturn(Optional.empty());
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        // Pin the TYPE, not just the message: GlobalExceptionHandler maps
        // NotFoundException -> 404; a bare RuntimeException falls to the
        // catch-all -> 500. Internal S2S callers + Swagger expect 404.
        assertThrows(com.innbucks.bookingservice.exception.NotFoundException.class,
                () -> service.getBookingById(UUID.randomUUID(), "u@example.com"));
    }

    @Test
    void getBookingById_guestBookingWithNullEmail_deniesNonAdminWithoutNpe() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        UUID id = UUID.randomUUID();
        // Guest checkout leaves userEmail null. A logged-in non-admin requesting
        // such a booking must get a clean 403, NOT a NullPointerException -> 500
        // from booking.getUserEmail().equals(...).
        Booking booking = Booking.builder().id(id).userEmail(null)
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN).build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        org.springframework.security.access.AccessDeniedException ex = assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service.getBookingById(id, "someone@example.com"));
        assertEquals("Access denied", ex.getMessage());
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
                mock(BookingItemRepository.class), stubClient(fx), publisher);

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
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx));

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
    void extendHold_rejectsPastHoldUntil_defenceInDepth() {
        // Bean validation's @Future fires only on the @Valid-bound controller
        // call. An S2S caller (today: payment-service) that passes a past
        // timestamp must be rejected at the service layer too — otherwise the
        // hold would silently shrink, expiring the booking right after the
        // customer started paying.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("u@example.com")
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN)
                .expiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(3))
                .build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.extendHold(id, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1)));
        assertTrue(ex.getMessage().contains("holdUntil must be in the future"),
                "actual: " + ex.getMessage());
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void confirmBooking_rejectsNegativeCashAmount_defenceInDepth() {
        // Bean validation's @DecimalMin(0) fires only on the @Valid-bound
        // controller call. The split-math reconstruction in applyLoyalty checks
        // the SUM but not individual field signs — a negative cash + an
        // offsetting positive points would still add up to totalAmount.
        // Reject negatives at the service entry instead.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        ConfirmBookingRequestDTO req = ConfirmBookingRequestDTO.builder()
                .cashAmount(new BigDecimal("-1.00"))
                .build();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.confirmBooking(UUID.randomUUID(), req));
        assertTrue(ex.getMessage().contains("cashAmount must be >= 0"),
                "actual: " + ex.getMessage());
        verify(bookingRepo, never()).findById(any(UUID.class));
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void confirmBooking_rejectsNegativePointsToUse_defenceInDepth() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        ConfirmBookingRequestDTO req = ConfirmBookingRequestDTO.builder()
                .pointsToUse(new BigDecimal("-100"))
                .build();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.confirmBooking(UUID.randomUUID(), req));
        assertTrue(ex.getMessage().contains("pointsToUse must be >= 0"),
                "actual: " + ex.getMessage());
        verify(bookingRepo, never()).findById(any(UUID.class));
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void confirmBooking_rejectsPayingWithPoints_loyaltyDecoupled() {
        // Booking is decoupled from loyalty: a ticket can't be paid for with
        // points (and never earns points). A confirm that tries to burn points is
        // rejected so we don't hand out a ticket for points that were never
        // redeemed. The booking stays PENDING — nothing is saved.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));

        UUID id = UUID.randomUUID();
        Booking booking = Booking.builder().id(id).userEmail("u@example.com")
                .status(Booking.BookingStatus.PENDING).totalAmount(BigDecimal.TEN)
                .expiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5))
                .build();
        when(bookingRepo.findById(id)).thenReturn(Optional.of(booking));

        ConfirmBookingRequestDTO req = ConfirmBookingRequestDTO.builder()
                .pointsToUse(new BigDecimal("100"))
                .build();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.confirmBooking(id, req));
        assertTrue(ex.getMessage().contains("Paying for tickets with loyalty points isn't available"),
                "actual: " + ex.getMessage());
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
        BookingService service = newService(bookingRepo, itemRepo, stubClient(fx));

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
                mock(BookingItemRepository.class), stubClient(fx));

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

    // ---- getByConfirmationNumber owner-scoping (OWASP A01 / BOLA) ----
    // The endpoint used to be public; it now requires auth and scopes to the
    // caller. A CUSTOMER who doesn't own the booking must get a 404
    // (NotFoundException) — not a 403 — so a non-owner can't confirm that a
    // given (low-entropy) confirmation number exists. Admins bypass the check.

    private static Booking confirmedBooking(String email, String phone) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .userEmail(email)
                .phoneNumber(phone)
                .confirmationNumber("INN-20260502-AB12CD")
                .status(Booking.BookingStatus.CONFIRMED)
                .totalAmount(new BigDecimal("100.00"))
                .build();
        booking.setItems(List.of(BookingItem.builder()
                .seatId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .categoryName("VIP")
                .rowLabel("GA")
                .seatNumber(1)
                .priceAtBooking(new BigDecimal("100.00"))
                .ticketNumber("20260502-12345A")
                .build()));
        return booking;
    }

    @Test
    void getByConfirmationNumber_owner_byEmail_returnsFullBookingWithQr() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));
        when(bookingRepo.findByConfirmationNumber("INN-20260502-AB12CD"))
                .thenReturn(Optional.of(confirmedBooking("alice@example.com", "+263782606983")));

        BookingResponseDTO dto = service.getByConfirmationNumber(
                "INN-20260502-AB12CD", "alice@example.com", null, false);

        assertEquals("INN-20260502-AB12CD", dto.getConfirmationNumber());
        assertEquals(1, dto.getItems().size());
        assertTrue(dto.getItems().get(0).getQrCode().startsWith("data:image/png;base64,"),
                "owner receives the full DTO including the scannable ticket QR");
    }

    @Test
    void getByConfirmationNumber_owner_byPhone_returnsBooking_forGuestWithNoEmail() {
        // Guest booking: no email on the booking, ownership proven by the phone
        // claim. The controller passes the caller phone already in E.164.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));
        when(bookingRepo.findByConfirmationNumber("INN-20260502-AB12CD"))
                .thenReturn(Optional.of(confirmedBooking(null, "+263782606983")));

        BookingResponseDTO dto = service.getByConfirmationNumber(
                "INN-20260502-AB12CD", null, "+263782606983", false);

        assertEquals("INN-20260502-AB12CD", dto.getConfirmationNumber());
    }

    @Test
    void getByConfirmationNumber_nonOwnerCustomer_throwsNotFound_notForbidden() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));
        when(bookingRepo.findByConfirmationNumber("INN-20260502-AB12CD"))
                .thenReturn(Optional.of(confirmedBooking("alice@example.com", "+263782606983")));

        // Different email AND different phone -> not the owner. 404, so the
        // caller can't even tell the confirmation number is real.
        assertThrows(com.innbucks.bookingservice.exception.NotFoundException.class,
                () -> service.getByConfirmationNumber(
                        "INN-20260502-AB12CD", "mallory@example.com", "+263770000000", false));
    }

    @Test
    void getByConfirmationNumber_admin_bypassesOwnership() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));
        when(bookingRepo.findByConfirmationNumber("INN-20260502-AB12CD"))
                .thenReturn(Optional.of(confirmedBooking("alice@example.com", "+263782606983")));

        // Admin identity matches neither email nor phone, but isAdmin=true wins.
        BookingResponseDTO dto = service.getByConfirmationNumber(
                "INN-20260502-AB12CD", "support@innbucks.com", null, true);

        assertEquals("INN-20260502-AB12CD", dto.getConfirmationNumber());
    }

    @Test
    void getByConfirmationNumber_unknownNumber_throwsNotFound() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingService service = newService(bookingRepo,
                mock(BookingItemRepository.class), mock(SeatServiceClient.class));
        when(bookingRepo.findByConfirmationNumber("INN-UNKNOWN"))
                .thenReturn(Optional.empty());

        assertThrows(com.innbucks.bookingservice.exception.NotFoundException.class,
                () -> service.getByConfirmationNumber("INN-UNKNOWN", "alice@example.com", null, false));
    }

    @Test
    void createBooking_attachesPerSeatQrCodeToEachItem() {
        RequestFixture fx = request(BigDecimal.TEN, BigDecimal.TEN);
        BookingService service = newService(mock(BookingRepository.class),
                mock(BookingItemRepository.class), stubClient(fx));

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

        // Admin path — second arg is the requester's organizerUuid, irrelevant when isAdmin=true.
        List<CategoryBookingDTO> result = service.getBookingsByCategory(categoryId, UUID.randomUUID(), true);

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

        List<CategoryBookingDTO> result = service.getBookingsByCategory(categoryId, UUID.randomUUID(), true);

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

    @Test
    void createBooking_guestPath_capturesTenantUserUuidFromEventService() {
        // Replaces the old "guest MUST NOT call event-service" optimization
        // guard — that optimization was the bug. Without tenantUserUuid on
        // the row, a guest's CONFIRMED ticket fails the gate scan
        // (WRONG_ORGANIZER, because TicketScanService matches the scanner's
        // organizerUuid JWT claim against booking.tenantUserUuid), and the
        // booking is invisible in every organizer report (those scope by
        // tenantUserUuid). Guest checkout is a wired, intended path, so the
        // event-service lookup must run for it too — same as logged-in.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"));

        UUID organizerUuid = UUID.randomUUID();
        EventServiceClient eventClient = mock(EventServiceClient.class);
        com.innbucks.bookingservice.dto.EventLookupDTO eventLookup =
                com.innbucks.bookingservice.dto.EventLookupDTO.builder()
                        .eventId(fx.eventId)
                        .tenantUserUuid(organizerUuid)
                        .build();
        when(eventClient.getEvent(fx.eventId))
                .thenReturn(ApiResult.ok("ok", eventLookup));

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<EventServiceClient> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(eventClient);

        BookingService service = new BookingService(bookingRepo, itemRepo,
                successfulInventory(), stubClient(fx), mock(ApplicationEventPublisher.class),
                new QrCodeGenerator(), null, provider, null,
                mock(PlatformTransactionManager.class));

        // Guest path: userEmail = null.
        service.createBooking(null, 2, "+263770000001", fx.request);

        // The lookup MUST run on the guest path.
        verify(eventClient).getEvent(fx.eventId);
        // And the captured organizer uuid MUST land on the saved booking,
        // so a later scan/ report can resolve to the organizer.
        // createBooking saves the booking more than once (PENDING insert,
        // post-items update); atLeastOnce matches the rest of this suite.
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, atLeastOnce()).save(saved.capture());
        assertEquals(organizerUuid, saved.getValue().getTenantUserUuid(),
                "guest booking must carry tenantUserUuid for scan + report scoping");
        assertNull(saved.getValue().getUserEmail(),
                "guest path still leaves userEmail null — the lookup is the only change");
    }

    @Test
    void createBooking_guestPath_eventServiceDown_proceedsWithNullTenantUserUuid() {
        // Failure-isolation contract — event-service being down does NOT
        // break a guest booking: lookupEvent swallows the failure and
        // returns null, the booking is created with tenantUserUuid=null
        // (legacy / unscannable, same fate a logged-in user's booking
        // gets under the same outage). Pinned because adding event-service
        // to the guest hot path made this surface area visible.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        RequestFixture fx = request(new BigDecimal("20.00"));

        EventServiceClient eventClient = mock(EventServiceClient.class);
        when(eventClient.getEvent(any())).thenThrow(new RuntimeException("event-service down"));

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<EventServiceClient> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(eventClient);

        BookingService service = new BookingService(bookingRepo, itemRepo,
                successfulInventory(), stubClient(fx), mock(ApplicationEventPublisher.class),
                new QrCodeGenerator(), null, provider, null,
                mock(PlatformTransactionManager.class));

        service.createBooking(null, 2, "+263770000001", fx.request);

        // createBooking saves the booking more than once (PENDING insert,
        // post-items update); atLeastOnce matches the rest of this suite.
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, atLeastOnce()).save(saved.capture());
        assertNull(saved.getValue().getTenantUserUuid(),
                "event-service outage leaves tenantUserUuid null — booking still succeeds");
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
                successfulInventory(), mock(SeatServiceClient.class), publisher,
                new QrCodeGenerator(), null, provider, null,
                mock(PlatformTransactionManager.class));
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
            b.getItems().add(BookingItem.builder()
                    .seatId(UUID.randomUUID())
                    .categoryId(UUID.randomUUID()) // releaseInventory groups by category
                    .build());
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

    @Test
    void getActiveItemCountsByCategories_excludesCancelled_butIncludesPendingAndConfirmed() {
        // seat-service's live per-category availableSeats is driven by this query.
        // Like the event-level sibling it MUST count PENDING + CONFIRMED (anything
        // not CANCELLED) so a category's "tickets left" and the event card stay in
        // lock-step the moment a customer starts checkout.
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        BookingService service = newService(bookingRepo, itemRepo, mock(SeatServiceClient.class));
        UUID categoryId = UUID.randomUUID();

        BookingItemRepository.CategoryActiveItemCount row =
                mock(BookingItemRepository.CategoryActiveItemCount.class);
        when(row.getCategoryId()).thenReturn(categoryId);
        when(row.getCount()).thenReturn(37L);
        when(itemRepo.countActiveItemsByCategoryIds(eq(List.of(categoryId)), eq(Booking.BookingStatus.CANCELLED)))
                .thenReturn(List.of(row));

        var result = service.getActiveItemCountsByCategories(List.of(categoryId));
        assertEquals(1, result.size());
        assertEquals(categoryId, result.get(0).getCategoryId());
        assertEquals(37L, result.get(0).getCount());

        // Guards the same status-parameter drift the event-level test guards.
        verify(itemRepo).countActiveItemsByCategoryIds(eq(List.of(categoryId)), eq(Booking.BookingStatus.CANCELLED));
        verify(itemRepo, never()).countActiveItemsByCategoryIds(any(), eq(Booking.BookingStatus.CONFIRMED));
        verify(itemRepo, never()).countActiveItemsByCategoryIds(any(), eq(Booking.BookingStatus.PENDING));
    }

    @Test
    void getActiveItemCountsByCategories_emptyOrNullInput_shortCircuits_noRepoCall() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingItemRepository itemRepo = mock(BookingItemRepository.class);
        BookingService service = newService(bookingRepo, itemRepo, mock(SeatServiceClient.class));

        assertTrue(service.getActiveItemCountsByCategories(List.of()).isEmpty());
        assertTrue(service.getActiveItemCountsByCategories(null).isEmpty());
        verify(itemRepo, never()).countActiveItemsByCategoryIds(any(), any());
    }
}
