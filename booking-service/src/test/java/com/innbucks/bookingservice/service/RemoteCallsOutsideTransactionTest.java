package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.GateLookupResponseDTO;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
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
 * Pins WHERE the scan, gate-lookup and reversal paths make their remote calls:
 * never while a transaction is open. Each remote client's answer records
 * {@link RecordingTransactionManager#inTransaction()} at the moment it is
 * called, and each local write records it too, so a regression that puts a
 * method-level {@code @Transactional} back (or moves a call into a phase) fails
 * here rather than as a pool held hostage by a slow user-service or
 * event-service.
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
    private final UserServiceClient userServiceClient = mock(UserServiceClient.class);
    private final EventServiceClient eventServiceClient = mock(EventServiceClient.class);

    private final UUID organizer = UUID.randomUUID();
    private final UUID teamMember = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- scan ------------------------------------------------------------------

    @Test
    void scan_asksUserServiceAndEventServiceWithNoTransaction_thenClaimsAndAuditsInOne() {
        TicketScanService scan = scanService(true);
        BookingItem item = confirmedItem();
        when(itemRepo.findByTicketNumberWithBooking("T-1")).thenAnswer(inv -> {
            record("read");
            return Optional.of(item);
        });
        when(userServiceClient.canScanEvent(eq(teamMember), eq(item.getBooking().getEventId()), any()))
                .thenAnswer(inv -> {
                    record("user-service");
                    return allowed(true);
                });
        when(eventServiceClient.getEventInternal(eq(item.getBooking().getEventId()), any())).thenAnswer(inv -> {
            record("event-service");
            return onToday();
        });
        when(itemRepo.claimRedemption(eq(item.getId()), any(), eq(teamMember), eq("Tariro")))
                .thenAnswer(inv -> {
                    record("claim");
                    return 1;
                });
        when(scanAttempts.save(any())).thenAnswer(inv -> {
            record("audit:" + ((ScanAttempt) inv.getArgument(0)).getOutcome());
            return inv.getArgument(0);
        });
        authenticateAsTeamMember();

        ScanTicketResponseDTO result = scan.scan("T-1", "Tariro");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
        assertThat(calls).containsExactly(
                "read|true", "user-service|false", "event-service|false", "claim|true", "audit:ALLOWED|true");
        // A read-only read, then ONE write transaction holding the claim AND its
        // audit row — they still commit or roll back together.
        assertThat(txManager.readOnlyFlags()).containsExactly(true, false);
        assertThat(txManager.outcomes()).containsExactly(COMMITTED, COMMITTED);
    }

    @Test
    void scan_refusedAfterTheAssignmentCheck_auditsInATransactionOfItsOwn() {
        TicketScanService scan = scanService(false);
        BookingItem item = confirmedItem();
        when(itemRepo.findByTicketNumberWithBooking("T-1")).thenReturn(Optional.of(item));
        when(userServiceClient.canScanEvent(any(), any(), any())).thenAnswer(inv -> {
            record("user-service");
            return allowed(false);
        });
        when(scanAttempts.save(any())).thenAnswer(inv -> {
            record("audit:" + ((ScanAttempt) inv.getArgument(0)).getOutcome());
            return inv.getArgument(0);
        });
        authenticateAsTeamMember();

        ScanTicketResponseDTO result = scan.scan("T-1", "Tariro");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.NOT_ASSIGNED_TO_EVENT);
        assertThat(calls).containsExactly("user-service|false", "audit:NOT_ASSIGNED_TO_EVENT|true");
        verify(itemRepo, never()).claimRedemption(any(), any(), any(), any());
        assertThat(txManager.readOnlyFlags()).containsExactly(true, false);
    }

    @Test
    void scan_eventServiceDown_isStillA503_andWritesNothing() {
        TicketScanService scan = scanService(true);
        BookingItem item = confirmedItem();
        when(itemRepo.findByTicketNumberWithBooking("T-1")).thenReturn(Optional.of(item));
        when(userServiceClient.canScanEvent(any(), any(), any())).thenReturn(allowed(true));
        when(eventServiceClient.getEventInternal(any(), any())).thenAnswer(inv -> {
            record("event-service");
            throw new IllegalStateException("event-service unreachable");
        });
        authenticateAsTeamMember();

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
        when(userServiceClient.canScanEvent(any(), any(), any())).thenReturn(allowed(true));
        when(itemRepo.claimRedemption(any(), any(), any(), any())).thenReturn(0);
        authenticateAsTeamMember();

        ScanTicketResponseDTO result = scan.scan("T-1", "Tariro");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALREADY_REDEEMED);
        // The first read is the read-only phase; the re-read is in the write one.
        assertThat(calls).containsExactly("read|true", "read|true");
        assertThat(txManager.readOnlyFlags()).containsExactly(true, false);
    }

    // ---- gate lookup -------------------------------------------------------

    @Test
    void gateLookup_readsInAReadOnlyTransaction_thenAsksUserServiceWithNone() {
        TicketScanService scan = scanService(false);
        GateLookupService lookup = new GateLookupService(bookingRepo, scan, noMeters());
        lookup.setTransactionManager(txManager);
        Booking booking = confirmedItem().getBooking();
        when(bookingRepo.findByConfirmationNumberWithItems("INN-1")).thenAnswer(inv -> {
            record("read");
            return Optional.of(booking);
        });
        when(userServiceClient.canScanEvent(any(), any(), any())).thenAnswer(inv -> {
            record("user-service");
            return allowed(true);
        });
        authenticateAsTeamMember();

        GateLookupResponseDTO result = lookup.lookup("inn-1");

        assertThat(result.getStatus()).isEqualTo(GateLookupResponseDTO.Status.FOUND);
        assertThat(result.getTickets()).hasSize(1);
        assertThat(calls).containsExactly("read|true", "user-service|false");
        assertThat(txManager.readOnlyFlags()).containsExactly(true);
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
                "read|true", "event-service|false", "read|true", "save|true", "publish|true");
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
        when(bookingRepo.findByIdWithItems(id))
                .thenReturn(Optional.of(asRead))
                .thenReturn(Optional.of(asReReadAfterAConcurrentChange));
        when(eventServiceClient.releaseAvailability(any(), anyInt(), any()))
                .thenReturn(ApiResult.ok("released", released()));

        assertThatThrownBy(() -> service.reverseConfirmedBooking(id, "admin@example.com"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(bookingRepo, never()).save(any());
        assertThat(asReReadAfterAConcurrentChange.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        assertThat(txManager.outcomes()).containsExactly(COMMITTED, ROLLED_BACK);
    }

    // ---- shape -----------------------------------------------------------------

    @Test
    void theEntryPointsCarryNoMethodLevelTransaction() throws Exception {
        for (Method m : new Method[] {
                TicketScanService.class.getMethod("scan", String.class, String.class),
                GateLookupService.class.getMethod("lookup", String.class),
                BookingService.class.getMethod("reverseConfirmedBooking", UUID.class, String.class)}) {
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
        TicketScanService scan = new TicketScanService(itemRepo, userServiceClient, scanAttempts,
                noMeters(), "ZW", events, market);
        scan.setTransactionManager(txManager);
        ReflectionTestUtils.setField(scan, "internalToken", "internal");
        ReflectionTestUtils.setField(scan, "assignmentCheckFailOpen", false);
        ReflectionTestUtils.setField(scan, "eventDayCheckEnabled", eventDayRule);
        ReflectionTestUtils.setField(scan, "eventDayCheckFailOpen", false);
        return scan;
    }

    @SuppressWarnings("unchecked")
    private BookingService bookingService(ApplicationEventPublisher publisher) {
        ObjectProvider<EventServiceClient> events = mock(ObjectProvider.class);
        when(events.getIfAvailable()).thenReturn(eventServiceClient);
        return new BookingService(bookingRepo, itemRepo, mock(CategoryInventoryRepository.class),
                mock(SeatServiceClient.class), publisher, new QrCodeGenerator(), null, events, null,
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

    private void authenticateAsTeamMember() {
        var auth = new UsernamePasswordAuthenticationToken("tariro@harare-arena.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_TEAM_MEMBER")));
        auth.setDetails(new JwtAuthDetails("tariro@harare-arena.co.zw", null, teamMember,
                organizer, "Tariro", "Chikomo"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static ApiResult<ScanAccessDTO> allowed(boolean allowed) {
        return ApiResult.<ScanAccessDTO>builder().code("200").message("ok")
                .data(ScanAccessDTO.builder().allowed(allowed).build()).build();
    }

    private static ApiResult<EventLookupDTO> onToday() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return ApiResult.ok("ok", EventLookupDTO.builder()
                .startDateTime(now.minusHours(1))
                .endDateTime(now.plusHours(2))
                .build());
    }

    private static AvailabilityResponseDTO released() {
        AvailabilityResponseDTO dto = new AvailabilityResponseDTO();
        dto.setAvailableTickets(10);
        return dto;
    }
}
