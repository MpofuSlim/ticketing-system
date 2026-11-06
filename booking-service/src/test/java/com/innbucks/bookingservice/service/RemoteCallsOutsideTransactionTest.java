package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.GateLookupResponseDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.dto.ScannerAccessDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.entity.ScanAttempt;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.repository.ScanAttemptRepository;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.testsupport.RecordingTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.innbucks.bookingservice.testsupport.RecordingTransactionManager.Outcome.COMMITTED;
import static com.innbucks.bookingservice.testsupport.RecordingTransactionManager.Outcome.ROLLED_BACK;
import static com.innbucks.bookingservice.testsupport.RecordingTransactionManager.inTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins WHERE the scan, gate-lookup, reversal and resurrecting-confirm paths
 * make their remote calls: never while a transaction is open. Each remote
 * client's answer records {@link RecordingTransactionManager#inTransaction()}
 * at the moment it is called, and each local write records it too, so a
 * regression that puts a method-level {@code @Transactional} back (or moves a
 * call into a phase) fails here rather than as a pool held hostage by a slow
 * event-service or seat-service.
 *
 * <p>Real Postgres behaviour — committed rows, a lockable row during the call —
 * is pinned by {@code RemoteCallsOutsideTransactionsPostgresIT}.
 */
class RemoteCallsOutsideTransactionTest {

    private final RecordingTransactionManager txManager = new RecordingTransactionManager();
    private final List<String> calls = new ArrayList<>();

    private final BookingItemRepository itemRepo = mock(BookingItemRepository.class);
    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final ScanAttemptRepository scanAttempts = mock(ScanAttemptRepository.class);
    private final EventServiceClient eventServiceClient = mock(EventServiceClient.class);
    private final SeatServiceClient seatServiceClient = mock(SeatServiceClient.class);
    private final CategoryInventoryRepository inventory = mock(CategoryInventoryRepository.class);

    private final UUID organizer = UUID.randomUUID();
    /** A super-app gate scanner's account (its organizer comes from event-service). */
    private final UUID superAppScanner = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- scan ------------------------------------------------------------------

    @Test
    void scan_asksEventServiceWithNoTransaction_thenClaimsAndAuditsInOne() {
        TicketScanService scan = scanService(true);
        BookingItem item = confirmedItem();
        when(itemRepo.findByTicketNumberWithBooking("T-1")).thenAnswer(inv -> {
            record("read");
            return Optional.of(item);
        });
        when(eventServiceClient.getEventInternal(eq(item.getBooking().getEventId()), any())).thenAnswer(inv -> {
            record("event-service");
            return onToday();
        });
        when(itemRepo.claimRedemption(eq(item.getId()), any(), eq(organizer), eq("Tariro")))
                .thenAnswer(inv -> {
                    record("claim");
                    return 1;
                });
        when(scanAttempts.save(any())).thenAnswer(inv -> {
            record("audit:" + ((ScanAttempt) inv.getArgument(0)).getOutcome());
            return inv.getArgument(0);
        });
        authenticateAsOrganizer();

        ScanTicketResponseDTO result = scan.scan("T-1", "Tariro");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
        assertThat(calls).containsExactly(
                "read|true", "event-service|false", "claim|true", "audit:ALLOWED|true");
        // A read-only read, then ONE write transaction holding the claim AND its
        // audit row — they still commit or roll back together.
        assertThat(txManager.readOnlyFlags()).containsExactly(true, false);
        assertThat(txManager.outcomes()).containsExactly(COMMITTED, COMMITTED);
    }

    @Test
    void scan_superApp_asksForTheScannersAccessWithNoTransaction_beforeTheRead() {
        TicketScanService scan = scanService(true);
        BookingItem item = confirmedItem();
        UUID eventId = item.getBooking().getEventId();
        when(eventServiceClient.scannerAccess(eq(superAppScanner), eq(eventId), any())).thenAnswer(inv -> {
            record("scanner-access");
            return ApiResult.ok("ok", ScannerAccessDTO.builder().allowed(true)
                    .organizerUuid(organizer).displayName("Rudo Sibanda").build());
        });
        when(itemRepo.findByTicketNumberWithBooking("T-1")).thenAnswer(inv -> {
            record("read");
            return Optional.of(item);
        });
        when(eventServiceClient.getEventInternal(eq(eventId), any())).thenAnswer(inv -> {
            record("event-service");
            return onToday();
        });
        when(itemRepo.claimRedemption(eq(item.getId()), any(), eq(superAppScanner), eq("Rudo Sibanda")))
                .thenAnswer(inv -> {
                    record("claim");
                    return 1;
                });
        when(scanAttempts.save(any())).thenAnswer(inv -> {
            record("audit:" + ((ScanAttempt) inv.getArgument(0)).getOutcome());
            return inv.getArgument(0);
        });
        authenticateAsSuperAppScanner();

        ScanTicketResponseDTO result = scan.scan("T-1", eventId, null);

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
        assertThat(calls).containsExactly("scanner-access|false", "read|true", "event-service|false",
                "claim|true", "audit:ALLOWED|true");
        assertThat(txManager.readOnlyFlags()).containsExactly(true, false);
        assertThat(txManager.outcomes()).containsExactly(COMMITTED, COMMITTED);
    }

    @Test
    void scan_refusedAfterTheEventServiceCheck_auditsInATransactionOfItsOwn() {
        TicketScanService scan = scanService(true);
        BookingItem item = confirmedItem();
        when(itemRepo.findByTicketNumberWithBooking("T-1")).thenReturn(Optional.of(item));
        when(eventServiceClient.getEventInternal(any(), any())).thenAnswer(inv -> {
            record("event-service");
            return lastWeek();
        });
        when(scanAttempts.save(any())).thenAnswer(inv -> {
            record("audit:" + ((ScanAttempt) inv.getArgument(0)).getOutcome());
            return inv.getArgument(0);
        });
        authenticateAsOrganizer();

        ScanTicketResponseDTO result = scan.scan("T-1", "Tariro");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.WRONG_EVENT_DAY);
        assertThat(calls).containsExactly("event-service|false", "audit:WRONG_EVENT_DAY|true");
        verify(itemRepo, never()).claimRedemption(any(), any(), any(), any());
        assertThat(txManager.readOnlyFlags()).containsExactly(true, false);
    }

    @Test
    void scan_eventServiceDown_isStillA503_andWritesNothing() {
        TicketScanService scan = scanService(true);
        BookingItem item = confirmedItem();
        when(itemRepo.findByTicketNumberWithBooking("T-1")).thenReturn(Optional.of(item));
        when(eventServiceClient.getEventInternal(any(), any())).thenAnswer(inv -> {
            record("event-service");
            throw new IllegalStateException("event-service unreachable");
        });
        authenticateAsOrganizer();

        assertThatThrownBy(() -> scan.scan("T-1", "Tariro"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("503");

        assertThat(calls).containsExactly("event-service|false");
        verify(itemRepo, never()).claimRedemption(any(), any(), any(), any());
        verify(scanAttempts, never()).save(any());
        // Only the read ran in a transaction.
        assertThat(txManager.readOnlyFlags()).containsExactly(true);
    }

    @Test
    void scan_alreadyRedeemed_rereadsInsideTheClaimTransaction() {
        TicketScanService scan = scanService(false);
        BookingItem item = confirmedItem();
        when(itemRepo.findByTicketNumberWithBooking("T-1")).thenAnswer(inv -> {
            record("read");
            return Optional.of(item);
        });
        when(itemRepo.claimRedemption(any(), any(), any(), any())).thenReturn(0);
        authenticateAsOrganizer();

        ScanTicketResponseDTO result = scan.scan("T-1", "Tariro");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALREADY_REDEEMED);
        // The first read is the read-only phase; the re-read is in the write one.
        assertThat(calls).containsExactly("read|true", "read|true");
        assertThat(txManager.readOnlyFlags()).containsExactly(true, false);
    }

    // ---- gate lookup -------------------------------------------------------

    @Test
    void gateLookup_superApp_asksEventServiceWithNoTransaction_thenReadsInAReadOnlyOne() {
        TicketScanService scan = scanService(false);
        GateLookupService lookup = new GateLookupService(bookingRepo, scan, noMeters());
        lookup.setTransactionManager(txManager);
        Booking booking = confirmedItem().getBooking();
        when(eventServiceClient.scannerAccess(eq(superAppScanner), eq(booking.getEventId()), any()))
                .thenAnswer(inv -> {
                    record("scanner-access");
                    return ApiResult.ok("ok", ScannerAccessDTO.builder().allowed(true)
                            .organizerUuid(organizer).displayName("Rudo Sibanda").build());
                });
        when(bookingRepo.findByConfirmationNumberWithItems("INN-1")).thenAnswer(inv -> {
            record("read");
            return Optional.of(booking);
        });
        authenticateAsSuperAppScanner();

        GateLookupResponseDTO result = lookup.lookup("inn-1", booking.getEventId());

        assertThat(result.getStatus()).isEqualTo(GateLookupResponseDTO.Status.FOUND);
        assertThat(result.getTickets()).hasSize(1);
        assertThat(calls).containsExactly("scanner-access|false", "read|true");
        assertThat(txManager.readOnlyFlags()).containsExactly(true);
    }

    @Test
    void gateLookup_organizer_readsInAReadOnlyTransaction_andAsksNobody() {
        TicketScanService scan = scanService(false);
        GateLookupService lookup = new GateLookupService(bookingRepo, scan, noMeters());
        lookup.setTransactionManager(txManager);
        Booking booking = confirmedItem().getBooking();
        when(bookingRepo.findByConfirmationNumberWithItems("INN-1")).thenAnswer(inv -> {
            record("read");
            return Optional.of(booking);
        });
        authenticateAsOrganizer();

        GateLookupResponseDTO result = lookup.lookup("inn-1");

        assertThat(result.getStatus()).isEqualTo(GateLookupResponseDTO.Status.FOUND);
        assertThat(calls).containsExactly("read|true");
        assertThat(txManager.readOnlyFlags()).containsExactly(true);
        org.mockito.Mockito.verifyNoInteractions(eventServiceClient);
    }

    // ---- reversal ------------------------------------------------------------

    @Test
    void reverse_releasesAtEventServiceWithNoTransaction_thenWritesInOne() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        BookingService service = bookingService(publisher);
        Booking booking = confirmedBooking(3L);
        when(bookingRepo.findByIdWithItems(booking.getId())).thenAnswer(inv -> {
            record("read");
            return Optional.of(booking);
        });
        when(bookingRepo.lockById(booking.getId())).thenAnswer(inv -> {
            record("lock");
            return Optional.of(booking);
        });
        when(eventServiceClient.releaseAvailability(eq(booking.getEventId()), eq(1), any())).thenAnswer(inv -> {
            record("event-service");
            return ApiResult.ok("released", released());
        });
        when(bookingRepo.save(any())).thenAnswer(inv -> {
            record("save");
            return inv.getArgument(0);
        });
        org.mockito.Mockito.doAnswer(inv -> {
            record("publish");
            return null;
        }).when(publisher).publishEvent(any(BookingDomainEvent.BookingCancelled.class));

        var result = service.reverseConfirmedBooking(booking.getId(), "admin@example.com");

        assertThat(result.getStatus()).isEqualTo(Booking.BookingStatus.CANCELLED);
        assertThat(booking.isAvailabilityReleased()).isTrue();
        assertThat(calls).containsExactly(
                "read|true", "event-service|false", "lock|true", "save|true", "publish|true");
        assertThat(txManager.readOnlyFlags()).containsExactly(true, false);
        assertThat(txManager.outcomes()).containsExactly(COMMITTED, COMMITTED);
    }

    @Test
    void reverse_bookingChangedDuringTheRelease_losesWithTheOptimisticLockFailure_andWritesNothing() {
        BookingService service = bookingService(mock(ApplicationEventPublisher.class));
        UUID id = UUID.randomUUID();
        Booking asRead = confirmedBooking(3L);
        asRead.setId(id);
        Booking asReReadAfterAConcurrentChange = confirmedBooking(4L);
        asReReadAfterAConcurrentChange.setId(id);
        when(bookingRepo.findByIdWithItems(id)).thenReturn(Optional.of(asRead));
        when(bookingRepo.lockById(id)).thenReturn(Optional.of(asReReadAfterAConcurrentChange));
        when(eventServiceClient.releaseAvailability(any(), anyInt(), any()))
                .thenReturn(ApiResult.ok("released", released()));

        assertThatThrownBy(() -> service.reverseConfirmedBooking(id, "admin@example.com"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(bookingRepo, never()).save(any());
        assertThat(asReReadAfterAConcurrentChange.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        assertThat(txManager.outcomes()).containsExactly(COMMITTED, ROLLED_BACK);
    }

    // ---- resurrect on confirm ------------------------------------------------------

    @Test
    void resurrect_asksSeatServiceWithNoTransaction_thenClaimsAndConfirmsInOne() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        BookingService service = bookingService(publisher);
        Booking booking = cancelledBooking(Booking.CancelReason.EXPIRED);
        UUID category = booking.getItems().get(0).getCategoryId();
        when(bookingRepo.lockById(booking.getId())).thenAnswer(inv -> {
            record("lock");
            return Optional.of(booking);
        });
        when(seatServiceClient.getCategory(category)).thenAnswer(inv -> {
            record("seat-service");
            return ApiResult.ok(CategoryLookupDTO.builder().seatCategoryId(category)
                    .eventId(booking.getEventId()).name("General").totalSeats(10).build());
        });
        when(inventory.tryClaim(category, 1)).thenAnswer(inv -> {
            record("claim");
            return 1;
        });
        when(bookingRepo.save(any())).thenAnswer(inv -> {
            record("save");
            return inv.getArgument(0);
        });
        org.mockito.Mockito.doAnswer(inv -> {
            record("publish");
            return null;
        }).when(publisher).publishEvent(any(BookingDomainEvent.BookingConfirmed.class));

        var result = service.confirmBooking(booking.getId(), null);

        assertThat(result.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        // Phase 1 reads the cancelled row under its lock and commits nothing;
        // seat-service is asked with no transaction open; phase 3 locks the row
        // again and claims, saves and publishes in one transaction.
        assertThat(calls).containsExactly(
                "lock|true", "seat-service|false", "lock|true", "claim|true", "save|true", "publish|true");
        assertThat(txManager.readOnlyFlags()).containsExactly(false, false);
        assertThat(txManager.outcomes()).containsExactly(COMMITTED, COMMITTED);
    }

    @Test
    void resurrect_seatServiceDown_writesNothing_andOpensNoSecondTransaction() {
        BookingService service = bookingService(mock(ApplicationEventPublisher.class));
        Booking booking = cancelledBooking(Booking.CancelReason.CUSTOMER_CANCELLED);
        when(bookingRepo.lockById(booking.getId())).thenReturn(Optional.of(booking));
        when(seatServiceClient.getCategory(any())).thenAnswer(inv -> {
            record("seat-service");
            throw new com.innbucks.bookingservice.exception.SeatServiceUnavailableException("down");
        });

        assertThatThrownBy(() -> service.confirmBooking(booking.getId(), null))
                .isInstanceOf(com.innbucks.bookingservice.exception.SeatServiceUnavailableException.class);

        assertThat(calls).containsExactly("seat-service|false");
        verify(inventory, never()).tryClaim(any(), anyInt());
        verify(bookingRepo, never()).save(any());
        assertThat(txManager.outcomes()).containsExactly(COMMITTED);
    }

    // ---- a sales agent's sale (V36) ---------------------------------------------------

    @Test
    void agentSale_asksSellerAccessAndTheEventWithNoTransaction_thenTakesTheCapLockInsideTheClaim() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        BookingService bookings = bookingService(publisher);
        UUID eventId = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID agentRow = UUID.randomUUID();
        UUID agentAccount = UUID.randomUUID();
        when(eventServiceClient.sellerAccess(eq(agentAccount), eq(eventId), any())).thenAnswer(inv -> {
            record("seller-access");
            return ApiResult.ok("Seller access", com.innbucks.bookingservice.dto.SellerAccessDTO.builder()
                    .allowed(true).agentId(agentRow).organizerUuid(organizer).displayName("Tendai Moyo")
                    .ticketCap(10).commissionPercent(new BigDecimal("5.00")).build());
        });
        when(eventServiceClient.salesWindow(eq(eventId), any())).thenAnswer(inv -> {
            record("sales-window");
            return ApiResult.ok("ok", com.innbucks.bookingservice.dto.EventSalesWindowDTO.builder().eventId(eventId)
                    .status(com.innbucks.bookingservice.dto.EventSalesWindowDTO.ON_SALE).tenantUserUuid(organizer)
                    .build());
        });
        when(seatServiceClient.getCategory(category)).thenAnswer(inv -> {
            record("seat-service");
            return ApiResult.ok(CategoryLookupDTO.builder().seatCategoryId(category).eventId(eventId)
                    .name("VIP").price(new BigDecimal("10.00")).totalSeats(100).build());
        });
        when(bookingRepo.lockAgentSales(anyInt(), anyInt())).thenAnswer(inv -> {
            record("agent-lock");
            return 1;
        });
        when(itemRepo.countActiveByAgentAndEvent(eq(agentRow), eq(eventId), any())).thenAnswer(inv -> {
            record("agent-count");
            return 0L;
        });
        when(inventory.tryClaim(category, 1)).thenAnswer(inv -> {
            record("claim");
            return 1;
        });
        Booking[] saved = new Booking[1];
        when(bookingRepo.save(any())).thenAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            if (saved[0].getId() == null) {
                saved[0].setId(UUID.randomUUID());
            }
            return saved[0];
        });
        when(bookingRepo.lockById(any())).thenAnswer(inv -> {
            record("lock");
            return Optional.of(saved[0]);
        });
        ObjectProvider<EventServiceClient> events = providerOf(eventServiceClient);
        BoxOfficeService boxOffice = new BoxOfficeService(bookings, events, "internal");
        com.innbucks.bookingservice.dto.BoxOfficeSaleRequestDTO request =
                new com.innbucks.bookingservice.dto.BoxOfficeSaleRequestDTO();
        request.setEventId(eventId);
        request.setCustomerName("Rufaro Moyo");
        com.innbucks.bookingservice.dto.CreateBookingRequestDTO.SeatItemRequest seat =
                new com.innbucks.bookingservice.dto.CreateBookingRequestDTO.SeatItemRequest();
        seat.setCategoryId(category);
        request.setSeats(List.of(seat));

        var sold = boxOffice.sell(request, null, "+263771234567",
                new BoxOfficeService.Caller(agentAccount, null, false, null));

        assertThat(sold.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        assertThat(calls).containsExactly("seller-access|false", "sales-window|false", "seat-service|false",
                "agent-lock|true", "agent-count|true", "claim|true", "lock|true");
        // The claim, then the cash confirm: two write transactions, both committed.
        assertThat(txManager.readOnlyFlags()).containsExactly(false, false);
        assertThat(txManager.outcomes()).containsExactly(COMMITTED, COMMITTED);
    }

    // ---- shape -----------------------------------------------------------------

    @Test
    void theEntryPointsCarryNoMethodLevelTransaction() throws Exception {
        for (Method m : new Method[] {
                TicketScanService.class.getMethod("scan", String.class, String.class),
                GateLookupService.class.getMethod("lookup", String.class),
                BoxOfficeService.class.getMethod("sell", com.innbucks.bookingservice.dto.BoxOfficeSaleRequestDTO.class,
                        String.class, String.class, BoxOfficeService.Caller.class),
                BookingService.class.getMethod("reverseConfirmedBooking", UUID.class, String.class),
                BookingService.class.getMethod("confirmBooking", UUID.class),
                BookingService.class.getMethod("confirmBooking", UUID.class,
                        com.innbucks.bookingservice.dto.ConfirmBookingRequestDTO.class)}) {
            assertThat(m.isAnnotationPresent(Transactional.class))
                    .as("%s.%s must not be @Transactional — it makes a remote call",
                            m.getDeclaringClass().getSimpleName(), m.getName())
                    .isFalse();
            assertThat(m.getDeclaringClass().isAnnotationPresent(Transactional.class)).isFalse();
        }
    }

    // ---- helpers -------------------------------------------------------------

    private void record(String what) {
        calls.add(what + "|" + inTransaction());
    }

    @SuppressWarnings("unchecked")
    private TicketScanService scanService(boolean eventDayRule) {
        ObjectProvider<EventServiceClient> events = mock(ObjectProvider.class);
        when(events.getIfAvailable()).thenReturn(eventServiceClient);
        ObjectProvider<MarketTimeZone> market = mock(ObjectProvider.class);
        when(market.getIfAvailable()).thenReturn(new MarketTimeZone("ZW"));
        TicketScanService scan = new TicketScanService(itemRepo, scanAttempts,
                noMeters(), "ZW", events, market);
        scan.setTransactionManager(txManager);
        ReflectionTestUtils.setField(scan, "internalToken", "internal");
        ReflectionTestUtils.setField(scan, "eventDayCheckEnabled", eventDayRule);
        ReflectionTestUtils.setField(scan, "eventDayCheckFailOpen", false);
        return scan;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EventServiceClient> providerOf(EventServiceClient client) {
        ObjectProvider<EventServiceClient> events = mock(ObjectProvider.class);
        when(events.getIfAvailable()).thenReturn(client);
        return events;
    }

    @SuppressWarnings("unchecked")
    private BookingService bookingService(ApplicationEventPublisher publisher) {
        ObjectProvider<EventServiceClient> events = mock(ObjectProvider.class);
        when(events.getIfAvailable()).thenReturn(eventServiceClient);
        return new BookingService(bookingRepo, itemRepo, inventory,
                seatServiceClient, publisher, new QrCodeGenerator(), events,
                txManager);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMeters() {
        return mock(ObjectProvider.class);
    }

    private BookingItem confirmedItem() {
        Booking booking = confirmedBooking(1L);
        return booking.getItems().get(0);
    }

    private Booking confirmedBooking(Long version) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .version(version)
                .eventId(UUID.randomUUID())
                .status(Booking.BookingStatus.CONFIRMED)
                .tenantUserUuid(organizer)
                .confirmationNumber("INN-1")
                .customerName("Rufaro Moyo")
                .totalAmount(new BigDecimal("10.00"))
                .items(new ArrayList<>())
                .build();
        booking.getItems().add(BookingItem.builder()
                .id(UUID.randomUUID())
                .booking(booking)
                .categoryId(UUID.randomUUID())
                .ticketNumber("T-1")
                .categoryName("General")
                .build());
        return booking;
    }

    private Booking cancelledBooking(Booking.CancelReason reason) {
        Booking booking = confirmedBooking(2L);
        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setCancelReason(reason);
        return booking;
    }

    private void authenticateAsOrganizer() {
        var auth = new UsernamePasswordAuthenticationToken("tariro@harare-arena.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        auth.setDetails(new JwtAuthDetails("tariro@harare-arena.co.zw", null, organizer,
                organizer, "Tariro", "Chikomo"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** The super-app session: CUSTOMER only, no organizer claim, no names. */
    private void authenticateAsSuperAppScanner() {
        var auth = new UsernamePasswordAuthenticationToken("+263772000111", null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new JwtAuthDetails(null, "+263772000111", superAppScanner, null, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static ApiResult<EventLookupDTO> onToday() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return ApiResult.ok("ok", EventLookupDTO.builder()
                .startDateTime(now.minusHours(1))
                .endDateTime(now.plusHours(2))
                .build());
    }

    private static ApiResult<EventLookupDTO> lastWeek() {
        LocalDateTime then = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
        return ApiResult.ok("ok", EventLookupDTO.builder()
                .startDateTime(then)
                .endDateTime(then.plusHours(2))
                .build());
    }

    private static AvailabilityResponseDTO released() {
        AvailabilityResponseDTO dto = new AvailabilityResponseDTO();
        dto.setAvailableTickets(10);
        return dto;
    }
}
