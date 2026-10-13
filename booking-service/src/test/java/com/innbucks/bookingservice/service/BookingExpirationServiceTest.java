package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.repository.ExpiryCandidate;
import com.innbucks.bookingservice.testsupport.RecordingTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.innbucks.bookingservice.testsupport.RecordingTransactionManager.Outcome.COMMITTED;
import static com.innbucks.bookingservice.testsupport.RecordingTransactionManager.Outcome.ROLLED_BACK;
import static com.innbucks.bookingservice.testsupport.RecordingTransactionManager.inTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The expiry sweep: candidates read without locks, then ONE short transaction
 * per booking. Real Postgres behaviour (SKIP LOCKED, the keyset cursor, a
 * failing row) is pinned by {@code BookingExpirySweepPostgresIT}.
 */
class BookingExpirationServiceTest {

    private final BookingRepository repo = mock(BookingRepository.class);
    private final CategoryInventoryRepository inventory = mock(CategoryInventoryRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final RecordingTransactionManager txManager = new RecordingTransactionManager();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BookingExpirationService sweep = new BookingExpirationService(repo, inventory, publisher,
            txManager, new BookingMetrics(registry));
    private final List<String> calls = new ArrayList<>();

    @Test
    void noLapsedHolds_doesNothing_andOpensNoTransaction() {
        when(repo.findExpiryCandidates(any(), any())).thenReturn(List.of());

        sweep.expirePending();

        verify(repo, never()).lockByIdSkipLocked(any());
        verifyNoInteractions(inventory, publisher);
        assertThat(txManager.outcomes()).isEmpty();
    }

    @Test
    void eachLapsedHold_isCancelledInATransactionOfItsOwn_releasingItsTickets() {
        Booking a = lapsed(UUID.randomUUID());
        Booking b = lapsed(UUID.randomUUID(), UUID.randomUUID());
        candidates(a, b);
        recordCalls();

        sweep.expirePending();

        verify(repo).markExpired(eq(a.getId()), any());
        verify(repo).markExpired(eq(b.getId()), any());
        verify(inventory).release(a.getItems().get(0).getCategoryId(), 1);
        verify(inventory).release(b.getItems().get(0).getCategoryId(), 1);
        verify(inventory).release(b.getItems().get(1).getCategoryId(), 1);
        assertThat(txManager.outcomes()).containsExactly(COMMITTED, COMMITTED);
        // Booking a's whole transaction finished before booking b's began.
        assertThat(calls).containsExactly(
                "lock " + a.getId() + "|true", "publish " + a.getId() + "|true",
                "lock " + b.getId() + "|true", "publish " + b.getId() + "|true");
        assertThat(skipped()).isZero();
    }

    @Test
    void theCancellationNotice_isPublishedInsideTheBookingsTransaction_soItFiresOnlyOnItsCommit() {
        Booking a = lapsed(UUID.randomUUID());
        candidates(a);
        recordCalls();

        sweep.expirePending();

        // Inside the transaction: the AFTER_COMMIT listeners deliver it once
        // that booking commits, and never if it rolls back. Published with no
        // transaction open, a transactional listener would drop it.
        assertThat(calls).contains("publish " + a.getId() + "|true");
        verify(publisher).publishEvent(any(BookingDomainEvent.BookingCancelled.class));
    }

    @Test
    void theRowIsLockedBeforeTheStock_andTheStockReleasedBeforeTheStatusChange() {
        Booking a = lapsed(UUID.randomUUID());
        candidates(a);

        sweep.expirePending();

        InOrder order = inOrder(repo, inventory, publisher);
        order.verify(repo).lockByIdSkipLocked(a.getId());
        order.verify(inventory).release(any(), anyInt());
        order.verify(repo).markExpired(eq(a.getId()), any());
        order.verify(publisher).publishEvent(any(BookingDomainEvent.BookingCancelled.class));
    }

    @Test
    void aBookingAnotherTransactionHolds_isSkipped_notWaitedFor_andNotCounted() {
        Booking held = lapsed(UUID.randomUUID());
        Booking free = lapsed(UUID.randomUUID());
        candidates(held, free);
        when(repo.lockByIdSkipLocked(held.getId())).thenReturn(Optional.empty()); // SKIP LOCKED

        sweep.expirePending();

        verify(repo, never()).markExpired(eq(held.getId()), any());
        verify(repo).markExpired(eq(free.getId()), any());
        verify(inventory, never()).release(eq(held.getItems().get(0).getCategoryId()), anyInt());
        assertThat(skipped()).isZero();
    }

    @Test
    void aBookingConfirmedOrExtendedSinceTheScan_isLeftAlone() {
        Booking confirmed = lapsed(UUID.randomUUID());
        confirmed.setStatus(Booking.BookingStatus.CONFIRMED);
        Booking extended = lapsed(UUID.randomUUID());
        extended.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20));
        candidates(confirmed, extended);

        sweep.expirePending();

        verify(repo, never()).markExpired(any(), any());
        verifyNoInteractions(inventory, publisher);
        assertThat(confirmed.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        assertThat(skipped()).isZero();
    }

    @Test
    void aFailingBooking_isRolledBack_counted_andDoesNotStopTheOnesAfterIt() {
        Booking failing = lapsed(UUID.randomUUID());
        Booking next = lapsed(UUID.randomUUID());
        candidates(failing, next);
        when(inventory.release(eq(failing.getItems().get(0).getCategoryId()), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        sweep.expirePending();

        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK, COMMITTED);
        verify(repo, never()).markExpired(eq(failing.getId()), any());
        verify(repo).markExpired(eq(next.getId()), any());
        ArgumentCaptor<BookingDomainEvent.BookingCancelled> published =
                ArgumentCaptor.forClass(BookingDomainEvent.BookingCancelled.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getValue().bookingId()).isEqualTo(next.getId());
        assertThat(skipped()).isEqualTo(1);
    }

    @Test
    void anUpdateThatTouchesNoRow_rollsTheReleaseBack_andPublishesNothing() {
        Booking a = lapsed(UUID.randomUUID());
        candidates(a);
        when(repo.markExpired(eq(a.getId()), any())).thenReturn(0);

        sweep.expirePending();

        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK);
        verifyNoInteractions(publisher);
        assertThat(skipped()).isZero();
    }

    @Test
    void theCursorPagesOnExpiresAtThenId_untilAShortPage() {
        sweep.pageSize = 2;
        Booking a = lapsed(UUID.randomUUID());
        Booking b = lapsed(UUID.randomUUID());
        Booking c = lapsed(UUID.randomUUID());
        stubLock(a, b, c);
        when(repo.findExpiryCandidates(any(), eq(Limit.of(2)))).thenReturn(List.of(candidate(a), candidate(b)));
        when(repo.findExpiryCandidatesAfter(any(), eq(b.getExpiresAt()), eq(b.getId()), eq(Limit.of(2))))
                .thenReturn(List.of(candidate(c)));

        sweep.expirePending();

        verify(repo).markExpired(eq(c.getId()), any());
        // A page shorter than the limit means nothing is left: no third query.
        verify(repo).findExpiryCandidatesAfter(any(), any(), any(), any());
        assertThat(txManager.outcomes()).containsExactly(COMMITTED, COMMITTED, COMMITTED);
    }

    @Test
    void theRunStopsAtItsBudget_andLeavesTheRestForTheNextRun() {
        sweep.runBudget = Duration.ZERO;
        Booking a = lapsed(UUID.randomUUID());
        Booking b = lapsed(UUID.randomUUID());
        candidates(a, b);

        sweep.expirePending();

        verify(repo).markExpired(eq(a.getId()), any());
        verify(repo, never()).lockByIdSkipLocked(b.getId());
    }

    @Test
    void theSkippedCounterIsRegisteredAtZero() {
        assertThat(registry.get(BookingMetrics.EXPIRY_SKIPPED).counter().count()).isZero();
    }

    // ---- shape -----------------------------------------------------------------

    @Test
    void theRunHoldsNoTransactionOfItsOwn_andKeepsItsScheduleAndLock() throws Exception {
        Method run = BookingExpirationService.class.getMethod("expirePending");
        assertThat(run.isAnnotationPresent(Transactional.class))
                .as("one transaction for the run would span every booking again").isFalse();
        assertThat(BookingExpirationService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(run.getAnnotation(Scheduled.class).fixedDelayString())
                .isEqualTo("${app.booking.expiration-poll-interval-ms:30000}");
        SchedulerLock lock = run.getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo("BookingExpirationService.expirePending");
        // PT5S, not PT15S: a longer floor stretches the poll interval (a 10 s
        // poll ran every 20 s). The ceiling stays far above the 25 s budget.
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT5S");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT5M");
        assertThat(BookingExpirationService.RUN_BUDGET).isLessThan(Duration.parse(lock.lockAtMostFor()));
        assertThat(BookingExpirationService.PAGE_SIZE).isEqualTo(200);
    }

    // ---- helpers -------------------------------------------------------------

    private void candidates(Booking... bookings) {
        stubLock(bookings);
        List<ExpiryCandidate> page = new ArrayList<>();
        for (Booking booking : bookings) {
            page.add(candidate(booking));
        }
        when(repo.findExpiryCandidates(any(), any())).thenReturn(page);
    }

    private void stubLock(Booking... bookings) {
        for (Booking booking : bookings) {
            when(repo.lockByIdSkipLocked(booking.getId())).thenReturn(Optional.of(booking));
            when(repo.markExpired(eq(booking.getId()), any())).thenReturn(1);
        }
    }

    private void recordCalls() {
        when(repo.lockByIdSkipLocked(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            calls.add("lock " + id + "|" + inTransaction());
            return Optional.of(lapsedWithId(id));
        });
        doAnswer(inv -> {
            BookingDomainEvent.BookingCancelled event = inv.getArgument(0);
            calls.add("publish " + event.bookingId() + "|" + inTransaction());
            return null;
        }).when(publisher).publishEvent(any(BookingDomainEvent.BookingCancelled.class));
    }

    private final Map<UUID, Booking> byId = new HashMap<>();

    private Booking lapsedWithId(UUID id) {
        return byId.get(id);
    }

    private Booking lapsed(UUID... categories) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .version(1L)
                .userEmail("u@example.com")
                .eventId(UUID.randomUUID())
                .confirmationNumber("INN-X")
                .status(Booking.BookingStatus.PENDING)
                .totalAmount(BigDecimal.TEN)
                .expiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .items(new ArrayList<>())
                .build();
        for (UUID category : categories) {
            booking.getItems().add(BookingItem.builder().booking(booking).categoryId(category).build());
        }
        byId.put(booking.getId(), booking);
        return booking;
    }

    private static ExpiryCandidate candidate(Booking booking) {
        return new ExpiryCandidate(booking.getId(), booking.getExpiresAt());
    }

    private double skipped() {
        return registry.get(BookingMetrics.EXPIRY_SKIPPED).counter().count();
    }
}
