package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.ConfirmBookingRequestDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.testsupport.OnSaleEvents;
import com.innbucks.bookingservice.testsupport.RecordingTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.innbucks.bookingservice.testsupport.RecordingTransactionManager.Outcome.COMMITTED;
import static com.innbucks.bookingservice.testsupport.RecordingTransactionManager.Outcome.ROLLED_BACK;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sales agents' sales inside BookingService (V36): the seller is stamped at
 * insert, the agent's ticket cap is counted under the agent's lock before any
 * stock is claimed (and a refusal holds nothing), an ONLINE sale asks the
 * buyer's unpaid limit and a CASH one does not, and the agent's commission is
 * stamped when the booking is CONFIRMED, by whichever path confirms it.
 */
class BookingServiceAgentSaleTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final UUID AGENT_ROW = UUID.randomUUID();
    private static final UUID AGENT_ACCOUNT = UUID.randomUUID();

    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final BookingItemRepository itemRepo = mock(BookingItemRepository.class);
    private final CategoryInventoryRepository inventory = mock(CategoryInventoryRepository.class);
    private final SeatServiceClient seats = mock(SeatServiceClient.class);
    private final EventServiceClient events = OnSaleEvents.client(ORGANIZER);
    private final RecordingTransactionManager txManager = new RecordingTransactionManager();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private BookingService service(String price) {
        when(seats.getCategory(CATEGORY)).thenReturn(ApiResult.ok("Category retrieved", CategoryLookupDTO.builder()
                .seatCategoryId(CATEGORY).eventId(EVENT).name("VIP").price(new BigDecimal(price))
                .totalSeats(1_000).availableSeats(1_000).build()));
        when(inventory.seedIfAbsent(any(), anyInt())).thenReturn(1);
        when(inventory.tryClaim(any(), anyInt())).thenReturn(1);
        // What @PrePersist does on a real save: the link token and created_at.
        when(bookingRepo.save(any())).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            if (b.getLinkToken() == null) {
                b.onCreate();
            }
            return b;
        });
        BookingService service = new BookingService(bookingRepo, itemRepo, inventory, seats,
                mock(ApplicationEventPublisher.class), new QrCodeGenerator(), OnSaleEvents.providerFor(events),
                txManager);
        service.setMetrics(new BookingMetrics(registry));
        return service;
    }

    private static CreateBookingRequestDTO request(int tickets) {
        CreateBookingRequestDTO r = new CreateBookingRequestDTO();
        r.setEventId(EVENT);
        r.setCustomerName("Rufaro Moyo");
        List<CreateBookingRequestDTO.SeatItemRequest> list = new ArrayList<>();
        for (int i = 0; i < tickets; i++) {
            CreateBookingRequestDTO.SeatItemRequest s = new CreateBookingRequestDTO.SeatItemRequest();
            s.setCategoryId(CATEGORY);
            list.add(s);
        }
        r.setSeats(list);
        return r;
    }

    private static BoxOfficeSeller agent(Integer cap, String percent) {
        return BoxOfficeSeller.agent(AGENT_ACCOUNT, "Tendai Moyo", AGENT_ROW, ORGANIZER, cap,
                percent == null ? null : new BigDecimal(percent));
    }

    private Booking savedBooking() {
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, atLeastOnce()).save(saved.capture());
        return saved.getValue();
    }

    // ---- the cap ------------------------------------------------------------------

    @Test
    void pastTheCap_isA409WithCapAndRemaining_takenUnderTheAgentsLock_andHoldsNothing() {
        BookingService service = service("10.00");
        when(itemRepo.countActiveByAgentAndEvent(AGENT_ROW, EVENT, Booking.BookingStatus.CANCELLED)).thenReturn(4L);

        ApiProblemException refused = assertThrows(ApiProblemException.class, () -> service.createBoxOfficeBooking(
                null, "+263771234567", request(2), agent(5, "5"), false));

        assertEquals(HttpStatus.CONFLICT, refused.getStatus());
        assertEquals("AGENT_CAP_REACHED", refused.getErrorCode());
        assertEquals(5, refused.getExtra().get("cap"));
        assertEquals(1L, refused.getExtra().get("remaining"));
        assertEquals("You may sell only 1 more ticket for this event (your limit is 5).", refused.getMessage());
        InOrder order = inOrder(bookingRepo, itemRepo);
        order.verify(bookingRepo).lockAgentSales(BookingService.AGENT_LOCK_NAMESPACE,
                BookingService.agentLockKey(AGENT_ROW, EVENT));
        order.verify(itemRepo).countActiveByAgentAndEvent(AGENT_ROW, EVENT, Booking.BookingStatus.CANCELLED);
        verify(inventory, never()).tryClaim(any(), anyInt());
        verify(inventory, never()).seedIfAbsent(any(), anyInt());
        verify(bookingRepo, never()).save(any());
        assertEquals(List.of(ROLLED_BACK), txManager.outcomes());
        assertEquals(1.0, registry.get(BookingMetrics.AGENT_SALE).tag("outcome", "cap_reached").counter().count());
    }

    @Test
    void aCapAlreadyReached_saysSo() {
        BookingService service = service("10.00");
        when(itemRepo.countActiveByAgentAndEvent(any(), any(), any())).thenReturn(7L);

        ApiProblemException refused = assertThrows(ApiProblemException.class, () -> service.createBoxOfficeBooking(
                null, "+263771234567", request(1), agent(5, null), false));

        assertEquals(0L, refused.getExtra().get("remaining"), "never negative");
        assertEquals("You have sold all 5 tickets you may sell for this event.", refused.getMessage());
    }

    @Test
    void withinTheCap_theLockAndCountComeFirst_thenTheStock_andTheBookingNamesItsAgent() {
        BookingService service = service("10.00");
        when(itemRepo.countActiveByAgentAndEvent(AGENT_ROW, EVENT, Booking.BookingStatus.CANCELLED)).thenReturn(3L);

        BookingResponseDTO held = service.createBoxOfficeBooking(null, "+263771234567", request(2),
                agent(5, "7.5"), true);

        InOrder order = inOrder(bookingRepo, itemRepo, inventory);
        order.verify(bookingRepo).lockAgentSales(anyInt(), anyInt());
        order.verify(itemRepo).countActiveByAgentAndEvent(AGENT_ROW, EVENT, Booking.BookingStatus.CANCELLED);
        order.verify(inventory).seedIfAbsent(eq(CATEGORY), anyInt());
        order.verify(inventory).tryClaim(CATEGORY, 2);
        order.verify(bookingRepo).save(any());
        Booking b = savedBooking();
        assertEquals(AGENT_ROW, b.getSoldByAgentId());
        assertEquals(AGENT_ACCOUNT, b.getSoldByUserUuid());
        assertEquals("Tendai Moyo", b.getSoldByName());
        assertEquals(new BigDecimal("7.50"), b.getAgentCommissionPercent());
        assertNull(b.getAgentCommissionAmount(), "nothing is earned until the sale is CONFIRMED");
        assertEquals(Booking.BookingStatus.PENDING, b.getStatus());
        assertEquals(List.of(COMMITTED), txManager.outcomes());
        // The seller's view, with the buyer's link token an online sale's checkout polls with.
        assertEquals(b.getLinkToken(), held.getViewToken());
        assertNotNull(held.getViewToken());
        assertEquals("Tendai Moyo", held.getSoldBy());
    }

    @Test
    void anAgentWithNoCap_takesNoLockAndCountsNothing() {
        BookingService service = service("10.00");

        service.createBoxOfficeBooking(null, "+263771234567", request(3), agent(null, "5"), false);

        verify(bookingRepo, never()).lockAgentSales(anyInt(), anyInt());
        verify(itemRepo, never()).countActiveByAgentAndEvent(any(), any(), any());
        assertEquals(AGENT_ROW, savedBooking().getSoldByAgentId());
    }

    @Test
    void theOrganizersSale_namesItsSellerAtInsert_andIsNoAgentsSale() {
        BookingService service = service("10.00");

        service.createBoxOfficeBooking(null, "+263771234567", request(1),
                BoxOfficeSeller.organizer(ORGANIZER, "organizer@example.com"), false);

        Booking b = savedBooking();
        assertEquals(ORGANIZER, b.getSoldByUserUuid());
        assertEquals("organizer@example.com", b.getSoldByName());
        assertNull(b.getSoldByAgentId());
        assertNull(b.getAgentCommissionPercent());
        verify(bookingRepo, never()).lockAgentSales(anyInt(), anyInt());
    }

    @Test
    void anAgentOfAnotherOrganizer_isRefusedBeforeAnythingIsLookedUpOrHeld() {
        BookingService service = service("10.00");
        BoxOfficeSeller otherTeam = BoxOfficeSeller.agent(AGENT_ACCOUNT, "Tendai Moyo", AGENT_ROW,
                UUID.randomUUID(), null, BigDecimal.ONE);

        ApiProblemException refused = assertThrows(ApiProblemException.class, () -> service.createBoxOfficeBooking(
                null, "+263771234567", request(1), otherTeam, false));

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatus());
        assertEquals("NOT_A_SALES_AGENT", refused.getErrorCode());
        verify(seats, never()).getCategory(any());
        verify(bookingRepo, never()).save(any());
    }

    // ---- the per-number unpaid limit --------------------------------------------------

    @Test
    void anOnlineSale_asksTheBuyersUnpaidLimit_aCashSaleDoesNot() {
        BookingService service = service("10.00");
        when(bookingRepo.countUnpaidSince(eq("+263771234567"), any(), any())).thenReturn(5L);

        ApiProblemException limited = assertThrows(ApiProblemException.class, () -> service.createBoxOfficeBooking(
                null, "+263771234567", request(1), agent(null, null), true));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getStatus());
        assertEquals("TOO_MANY_UNPAID_BOOKINGS", limited.getErrorCode());
        verify(bookingRepo, never()).save(any());

        // The organizer's own online sale is held to it too.
        assertThrows(ApiProblemException.class, () -> service.createBoxOfficeBooking(null, "+263771234567",
                request(1), BoxOfficeSeller.organizer(ORGANIZER, "o"), true));

        clearInvocations(bookingRepo);
        service.createBoxOfficeBooking(null, "+263771234567", request(1), agent(null, null), false);
        verify(bookingRepo, never()).countUnpaidSince(any(), any(), any());
    }

    // ---- commission ---------------------------------------------------------------------

    private Booking pendingAgentBooking(String total, String percent) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .version(1L)
                .eventId(EVENT)
                .tenantUserUuid(ORGANIZER)
                .confirmationNumber("INN-1")
                .status(Booking.BookingStatus.PENDING)
                .totalAmount(new BigDecimal(total))
                .soldByAgentId(AGENT_ROW)
                .soldByUserUuid(AGENT_ACCOUNT)
                .soldByName("Tendai Moyo")
                .agentCommissionPercent(percent == null ? null : new BigDecimal(percent))
                .items(new ArrayList<>())
                .build();
        booking.getItems().add(BookingItem.builder().id(UUID.randomUUID()).booking(booking).categoryId(CATEGORY)
                .categoryName("VIP").ticketNumber("20261120-7KQ4M-9XZ2B").build());
        when(bookingRepo.lockById(booking.getId())).thenReturn(Optional.of(booking));
        return booking;
    }

    @Test
    void aCashSale_stampsTheCommissionAtOnce_halfUpToTheCent() {
        BookingService service = service("10.00");
        Booking booking = pendingAgentBooking("33.33", "7.50");

        BookingResponseDTO sold = service.confirmBoxOfficeSale(booking.getId(), AGENT_ACCOUNT, "Tendai Moyo");

        // 33.33 x 7.5% = 2.49975 -> 2.50
        assertEquals(new BigDecimal("2.50"), booking.getAgentCommissionAmount());
        assertEquals(Booking.BookingStatus.CONFIRMED, sold.getStatus());
        assertEquals("CASH", booking.getPaymentRail());
        assertEquals("Tendai Moyo", booking.getSoldByName(), "the seller stamped at insert is kept");
    }

    @Test
    void anOnlineSale_stampsTheCommissionWhenPaymentServiceConfirms() {
        BookingService service = service("10.00");
        Booking booking = pendingAgentBooking("300.00", "5.00");

        service.confirmBooking(booking.getId(), ConfirmBookingRequestDTO.builder().paymentRail("ECOCASH").build());

        assertEquals(new BigDecimal("15.00"), booking.getAgentCommissionAmount());
        assertEquals("ECOCASH", booking.getPaymentRail());
    }

    @Test
    void aResurrectedOnlineSale_stampsTheCommissionToo() {
        BookingService service = service("10.00");
        Booking booking = pendingAgentBooking("150.00", "10.00");
        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setCancelReason(Booking.CancelReason.EXPIRED);

        service.confirmBooking(booking.getId(), ConfirmBookingRequestDTO.builder().paymentRail("ECOCASH").build());

        assertEquals(Booking.BookingStatus.CONFIRMED, booking.getStatus());
        assertEquals(new BigDecimal("15.00"), booking.getAgentCommissionAmount());
    }

    @Test
    void aZeroPercentAgent_earnsZero_andAnOrganizersSale_earnsNothing() {
        BookingService service = service("10.00");
        Booking zero = pendingAgentBooking("80.00", "0.00");
        service.confirmBoxOfficeSale(zero.getId(), AGENT_ACCOUNT, "Tendai Moyo");
        assertEquals(new BigDecimal("0.00"), zero.getAgentCommissionAmount());

        Booking organizers = pendingAgentBooking("80.00", null);
        organizers.setSoldByAgentId(null);
        service.confirmBoxOfficeSale(organizers.getId(), ORGANIZER, "organizer@example.com");
        assertNull(organizers.getAgentCommissionAmount());
    }

    @Test
    void aReplayedConfirm_neverRestampsTheCommission() {
        BookingService service = service("10.00");
        Booking booking = pendingAgentBooking("100.00", "5.00");
        booking.setStatus(Booking.BookingStatus.CONFIRMED);
        booking.setAgentCommissionAmount(new BigDecimal("4.00"));

        service.confirmBooking(booking.getId(), null);

        assertEquals(new BigDecimal("4.00"), booking.getAgentCommissionAmount());
    }

    @Test
    void theCommissionArithmetic_isHalfUpToTheCent() {
        assertEquals(new BigDecimal("0.01"), BoxOfficeSeller.commissionOn(new BigDecimal("0.10"), new BigDecimal("5")));
        assertEquals(new BigDecimal("0.00"), BoxOfficeSeller.commissionOn(new BigDecimal("0.09"), new BigDecimal("5")));
        assertEquals(new BigDecimal("12.35"), BoxOfficeSeller.commissionOn(new BigDecimal("123.45"),
                new BigDecimal("10.00")));
        assertEquals(new BigDecimal("150.00"), BoxOfficeSeller.commissionOn(new BigDecimal("150.00"),
                new BigDecimal("100")));
        assertNull(BoxOfficeSeller.commissionOn(null, BigDecimal.ONE));
        // A percentage with more than two decimals is rounded when the seller is built.
        assertEquals(new BigDecimal("12.35"), BoxOfficeSeller.agent(AGENT_ACCOUNT, "x", AGENT_ROW, ORGANIZER,
                null, new BigDecimal("12.345")).commissionPercent());
    }

    @Test
    void theLockKey_isStable_andTheNamespaceSpellsAGNT() {
        UUID a = UUID.fromString("4b1e2c3d-5f60-4a7b-8c9d-0e1f2a3b4c5d");
        UUID e = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        assertEquals(31 * a.hashCode() + e.hashCode(), BookingService.agentLockKey(a, e));
        assertEquals(BookingService.agentLockKey(a, e), BookingService.agentLockKey(a, e));
        assertEquals("AGNT", new String(java.nio.ByteBuffer.allocate(4)
                .putInt(BookingService.AGENT_LOCK_NAMESPACE).array(), java.nio.charset.StandardCharsets.US_ASCII));
    }
}
