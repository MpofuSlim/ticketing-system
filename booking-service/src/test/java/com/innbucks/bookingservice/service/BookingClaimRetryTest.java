package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.BookingConflictException;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.testsupport.RecordingTransactionManager;
import com.innbucks.common.ticket.TicketNumbers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.innbucks.bookingservice.testsupport.RecordingTransactionManager.Outcome.COMMITTED;
import static com.innbucks.bookingservice.testsupport.RecordingTransactionManager.Outcome.ROLLED_BACK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The claim transaction's canonical lock order, its one deadlock retry and its
 * bounded reference-collision retry. The real deadlock, the real 40P01 and the
 * real 23505 through the JDBC stack are pinned against Postgres by
 * {@code CategoryInventoryConcurrencyIT}, {@code BookingClaimRetryPostgresIT}
 * and {@code BookingReferenceCollisionPostgresIT}; this pins the decisions.
 */
class BookingClaimRetryTest {

    // Postgres order (unsigned bytes): A < B. Java's UUID.compareTo says B < A.
    private static final UUID A = UUID.fromString("7fffffff-0000-0000-0000-000000000000");
    private static final UUID B = UUID.fromString("80000000-0000-0000-0000-000000000000");

    private final UUID eventId = UUID.randomUUID();
    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final BookingItemRepository itemRepo = mock(BookingItemRepository.class);
    private final CategoryInventoryRepository inventory = mock(CategoryInventoryRepository.class);
    private final SeatServiceClient seats = mock(SeatServiceClient.class);
    private final RecordingTransactionManager txManager = new RecordingTransactionManager();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private BookingService service;

    @BeforeEach
    void setUp() {
        stubCategory(A, "Bay 12");
        stubCategory(B, "Bay 13");
        when(inventory.seedIfAbsent(any(), anyInt())).thenReturn(0);
        when(inventory.tryClaim(any(), anyInt())).thenReturn(1);
        when(itemRepo.saveAllAndFlush(anyList())).thenAnswer(inv -> inv.getArgument(0));
        service = new BookingService(bookingRepo, itemRepo, inventory, seats, mock(ApplicationEventPublisher.class),
                new QrCodeGenerator(), null, txManager);
        service.setMetrics(new BookingMetrics(registry));
    }

    @Test
    void categoriesAreClaimedInTheCanonicalOrder_whateverOrderTheRequestNamedThem() {
        service.createBooking("u@example.com", "+263771234567", request(B, A, B));

        InOrder order = inOrder(inventory);
        order.verify(inventory).tryClaim(A, 1);
        order.verify(inventory).tryClaim(B, 2);
    }

    @Test
    void ticketsAreStillIssuedInRequestOrder() {
        BookingResponseDTO created = service.createBooking("u@example.com", "+263771234567", request(B, A, B));

        assertThat(created.getItems()).extracting("categoryId").containsExactly(B, A, B);
    }

    @Test
    void aDeadlockOnTheFirstAttempt_isRetriedOnce_fromFreshEntities() {
        when(itemRepo.saveAllAndFlush(anyList()))
                .thenThrow(lockFailure("40P01"))
                .thenAnswer(inv -> inv.getArgument(0));

        BookingResponseDTO created = service.createBooking("u@example.com", "+263771234567", request(A, B));

        assertThat(created.getStatus()).isEqualTo(Booking.BookingStatus.PENDING);
        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK, COMMITTED);
        // The second attempt saved a DIFFERENT booking with its own numbers:
        // re-saving the first one would fail on its stale version.
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, times(3)).save(saved.capture());
        Booking first = saved.getAllValues().get(0);
        Booking second = saved.getAllValues().get(1);
        assertThat(second).isNotSameAs(first);
        assertThat(second.getConfirmationNumber()).isNotEqualTo(first.getConfirmationNumber());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BookingItem>> items = ArgumentCaptor.forClass(List.class);
        verify(itemRepo, times(2)).saveAllAndFlush(items.capture());
        assertThat(items.getAllValues().get(1)).allSatisfy(i -> assertThat(i.getBooking()).isSameAs(second));
        assertThat(items.getAllValues().get(1)).extracting(BookingItem::getTicketNumber)
                .doesNotContainAnyElementsOf(items.getAllValues().get(0).stream()
                        .map(BookingItem::getTicketNumber).toList());
        assertThat(retries("succeeded")).isEqualTo(1);
    }

    @Test
    void aSerializationFailure_isRetriedToo() {
        when(inventory.tryClaim(A, 1)).thenThrow(lockFailure("40001")).thenReturn(1);

        service.createBooking("u@example.com", "+263771234567", request(A));

        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK, COMMITTED);
        assertThat(retries("succeeded")).isEqualTo(1);
    }

    @Test
    void theSameFailureTwice_isA503TryAgain_notA500() {
        when(inventory.tryClaim(A, 1)).thenThrow(lockFailure("40P01"));

        assertThatThrownBy(() -> service.createBooking("u@example.com", "+263771234567", request(A)))
                .isInstanceOfSatisfying(ApiProblemException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getErrorCode()).isEqualTo("TRY_AGAIN");
                    assertThat(ex.getMessage()).isEqualTo("We couldn't reserve your tickets just now. Please try again.");
                });
        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK, ROLLED_BACK);
        verify(inventory, times(2)).tryClaim(A, 1);
        assertThat(retries("failed")).isEqualTo(1);
    }

    @Test
    void aRetryThatIsSoldOut_answersSoldOut_andCountsAsRefused() {
        when(inventory.tryClaim(A, 1)).thenThrow(lockFailure("40P01")).thenReturn(0);

        assertThatThrownBy(() -> service.createBooking("u@example.com", "+263771234567", request(A)))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("Not enough tickets left");
        assertThat(retries("refused")).isEqualTo(1);
    }

    @Test
    void anyOtherFailure_isNotRetried() {
        when(inventory.tryClaim(A, 1)).thenThrow(new DataIntegrityViolationException("duplicate key",
                new SQLException("duplicate key value", "23505")));

        assertThatThrownBy(() -> service.createBooking("u@example.com", "+263771234567", request(A)))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(inventory, times(1)).tryClaim(A, 1);
        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK);
        assertThat(retries("succeeded") + retries("failed") + retries("refused")).isZero();
    }

    // ---- reference collisions ------------------------------------------------

    @Test
    void aTicketNumberCollision_isRetried_withFreshReferences() {
        BookingReferences references = spy(new BookingReferences());
        service.setReferences(references);
        when(itemRepo.saveAllAndFlush(anyList()))
                .thenThrow(collision("uk_booking_items_ticket_number"))
                .thenAnswer(inv -> inv.getArgument(0));

        BookingResponseDTO created = service.createBooking("u@example.com", "+263771234567", request(A, B));

        assertThat(created.getStatus()).isEqualTo(Booking.BookingStatus.PENDING);
        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK, COMMITTED);
        verify(references, times(2)).nextConfirmationNumber();
        verify(references, times(4)).nextTicketNumber();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BookingItem>> items = ArgumentCaptor.forClass(List.class);
        verify(itemRepo, times(2)).saveAllAndFlush(items.capture());
        List<String> first = items.getAllValues().get(0).stream().map(BookingItem::getTicketNumber).toList();
        List<String> second = items.getAllValues().get(1).stream().map(BookingItem::getTicketNumber).toList();
        assertThat(second).doesNotContainAnyElementsOf(first).allMatch(TicketNumbers::isCurrent);
        assertThat(created.getItems()).extracting("ticketNumber").containsExactlyElementsOf(second);
        assertThat(collisions("ticket")).isEqualTo(1);
        assertThat(collisions("confirmation")).isZero();
        // Not lock contention: the deadlock meter stays still.
        assertThat(retries("succeeded") + retries("failed") + retries("refused")).isZero();
    }

    @Test
    void aConfirmationNumberCollision_isRetried_withAFreshBooking() {
        when(itemRepo.saveAllAndFlush(anyList()))
                .thenThrow(collision("uk_bookings_confirmation_number"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.createBooking("u@example.com", "+263771234567", request(A));

        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK, COMMITTED);
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, times(3)).save(saved.capture());
        Booking first = saved.getAllValues().get(0);
        Booking second = saved.getAllValues().get(1);
        assertThat(second).isNotSameAs(first);
        assertThat(second.getConfirmationNumber()).isNotEqualTo(first.getConfirmationNumber());
        assertThat(collisions("confirmation")).isEqualTo(1);
        assertThat(collisions("ticket")).isZero();
    }

    @Test
    void anotherUniqueConstraint_isNotRetried() {
        when(itemRepo.saveAllAndFlush(anyList())).thenThrow(collision("uq_active_booking_item_per_seat"));

        assertThatThrownBy(() -> service.createBooking("u@example.com", "+263771234567", request(A)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK);
        assertThat(collisions("ticket") + collisions("confirmation")).isZero();
    }

    @Test
    void aCollisionOnEveryAttempt_is503TryAgain_afterThreeRetries_withNothingHeld() {
        when(itemRepo.saveAllAndFlush(anyList())).thenThrow(collision("uk_booking_items_ticket_number"));

        assertThatThrownBy(() -> service.createBooking("u@example.com", "+263771234567", request(A)))
                .isInstanceOfSatisfying(ApiProblemException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getErrorCode()).isEqualTo("TRY_AGAIN");
                    assertThat(ex.getMessage()).isEqualTo("We couldn't reserve your tickets just now. Please try again.");
                });
        // 1 + MAX_COLLISION_RETRIES attempts, every one rolled back (its stock claim with it).
        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK, ROLLED_BACK, ROLLED_BACK, ROLLED_BACK);
        verify(inventory, times(4)).tryClaim(A, 1);
        assertThat(BookingService.MAX_COLLISION_RETRIES).isEqualTo(3);
        // Every clash is counted, the one that exhausted the retries included.
        assertThat(collisions("ticket")).isEqualTo(4);
        assertThat(retries("succeeded") + retries("failed") + retries("refused")).isZero();
    }

    @Test
    void collisionsDoNotSpendTheDeadlockRetry_andADeadlockDoesNotSpendTheCollisionRetries() {
        when(itemRepo.saveAllAndFlush(anyList()))
                .thenThrow(lockFailure("40P01"))
                .thenThrow(collision("uk_booking_items_ticket_number"))
                .thenThrow(collision("uk_bookings_confirmation_number"))
                .thenThrow(collision("uk_booking_items_ticket_number"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.createBooking("u@example.com", "+263771234567", request(A));

        assertThat(txManager.outcomes())
                .containsExactly(ROLLED_BACK, ROLLED_BACK, ROLLED_BACK, ROLLED_BACK, COMMITTED);
        assertThat(retries("succeeded")).isEqualTo(1);
        assertThat(collisions("ticket")).isEqualTo(2);
        assertThat(collisions("confirmation")).isEqualTo(1);
    }

    @Test
    void theDeadlockBudgetIsStillOne_withACollisionInBetween() {
        when(itemRepo.saveAllAndFlush(anyList()))
                .thenThrow(lockFailure("40P01"))
                .thenThrow(collision("uk_booking_items_ticket_number"))
                .thenThrow(lockFailure("40P01"));

        assertThatThrownBy(() -> service.createBooking("u@example.com", "+263771234567", request(A)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("TRY_AGAIN"));
        assertThat(txManager.outcomes()).containsExactly(ROLLED_BACK, ROLLED_BACK, ROLLED_BACK);
        assertThat(retries("failed")).isEqualTo(1);
        assertThat(collisions("ticket")).isEqualTo(1);
    }

    @Test
    void collisionsExhaustedAfterADeadlockRetry_countTheDeadlockRetryAsRefused() {
        // The caller gets a 503, yet this is not a `failed`: `failed` means a
        // second deadlock, and the attempts after the first one clashed instead.
        // The 503 is the collision budget's, counted on reference_collision.
        when(itemRepo.saveAllAndFlush(anyList()))
                .thenThrow(lockFailure("40P01"))
                .thenThrow(collision("uk_booking_items_ticket_number"));

        assertThatThrownBy(() -> service.createBooking("u@example.com", "+263771234567", request(A)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("TRY_AGAIN"));
        assertThat(txManager.outcomes()).hasSize(5).containsOnly(ROLLED_BACK);
        assertThat(retries("refused")).isEqualTo(1);
        assertThat(retries("failed") + retries("succeeded")).isZero();
        assertThat(collisions("ticket")).isEqualTo(4);
    }

    @Test
    void everyCollisionKindIsRegisteredAtZero() {
        for (String reference : List.of("ticket", "confirmation")) {
            assertThat(collisions(reference)).as(reference).isZero();
        }
    }

    @Test
    void collidedReference_readsTheConstraint_fromTheChainPostgresActuallyThrows() {
        assertThat(BookingService.collidedReference(collision("uk_booking_items_ticket_number")))
                .isEqualTo(BookingMetrics.ReferenceKind.TICKET);
        assertThat(BookingService.collidedReference(collision("uk_bookings_confirmation_number")))
                .isEqualTo(BookingMetrics.ReferenceKind.CONFIRMATION);
        // Compared without regard to case.
        assertThat(BookingService.collidedReference(collision("UK_BOOKING_ITEMS_TICKET_NUMBER")))
                .isEqualTo(BookingMetrics.ReferenceKind.TICKET);
        // Wrapped further still.
        assertThat(BookingService.collidedReference(new RuntimeException(collision("uk_bookings_confirmation_number"))))
                .isEqualTo(BookingMetrics.ReferenceKind.CONFIRMATION);
    }

    @Test
    void collidedReference_fallsBackToHibernatesParse_whenNoDriverMessageNamesOne() {
        SQLException bare = new SQLException("duplicate key", "23505");
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("could not execute statement", bare,
                        "insert into booking_items ...", ConstraintKind.UNIQUE, "uk_booking_items_ticket_number"));

        assertThat(BookingService.collidedReference(ex)).isEqualTo(BookingMetrics.ReferenceKind.TICKET);
    }

    @Test
    void collidedReference_trustsTheDriversOwnMessage_overBoundValuesInTheBatchMessage() {
        // An organizer-chosen category name lands in the batch message's bound
        // values, BEFORE Postgres' text, so Hibernate's first-match parse can be
        // fooled. The driver's own exception names the real constraint.
        String spoof = "violates unique constraint \"uk_booking_items_ticket_number\"";
        SQLException driver = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"uq_active_booking_item_per_seat\"", "23505");
        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 insert into booking_items values ('"
                + spoof + "') was aborted: " + driver.getMessage(), "23505", 0, new int[0], driver);
        batch.setNextException(driver);
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute batch",
                new org.hibernate.exception.ConstraintViolationException("could not execute batch", batch,
                        "insert into booking_items ...", ConstraintKind.UNIQUE, "uk_booking_items_ticket_number"));

        assertThat(BookingService.collidedReference(ex)).isNull();
    }

    @Test
    void collidedReference_isNull_forEverythingElse() {
        assertThat(BookingService.collidedReference(lockFailure("40P01"))).isNull();
        assertThat(BookingService.collidedReference(new DataIntegrityViolationException("duplicate key",
                new SQLException("duplicate key value", "23505")))).isNull();
        // Our constraint's name under another SQLState is not a unique violation.
        assertThat(BookingService.collidedReference(new DataIntegrityViolationException("x", new SQLException(
                "ERROR: violates unique constraint \"uk_booking_items_ticket_number\"", "23514")))).isNull();
        assertThat(BookingService.collidedReference(new BookingConflictException("sold out"))).isNull();
        assertThat(BookingService.collidedReference(null)).isNull();
    }

    @Test
    void collidedReference_terminatesOnACyclicChain() {
        SQLException looped = new SQLException("duplicate key value", "23505");
        looped.setNextException(looped);
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(BookingService.collidedReference(looped)).isNull();
        assertThat(BookingService.collidedReference(a)).isNull();
    }

    @Test
    void everyRetryOutcomeIsRegisteredAtZero() {
        for (String outcome : List.of("succeeded", "failed", "refused")) {
            assertThat(retries(outcome)).as(outcome).isZero();
        }
    }

    @Test
    void retryableSqlState_readsTheWholeCauseChain() {
        assertThat(BookingService.retryableSqlState(lockFailure("40P01"))).isEqualTo("40P01");
        assertThat(BookingService.retryableSqlState(new RuntimeException(lockFailure("40001")))).isEqualTo("40001");
        assertThat(BookingService.retryableSqlState(new RuntimeException("no SQL here"))).isNull();
        assertThat(BookingService.retryableSqlState(lockFailure("55P03"))).isNull(); // lock_not_available
    }

    // ---- helpers -------------------------------------------------------------

    private static CannotAcquireLockException lockFailure(String sqlState) {
        return new CannotAcquireLockException("could not execute statement",
                new SQLException("ERROR: forced", sqlState));
    }

    /**
     * The chain Spring, Hibernate 7 and pgjdbc produce for a batched INSERT
     * that hit a unique constraint: Spring's translation, Hibernate's
     * ConstraintViolationException (its name parsed from the batch message),
     * pgjdbc's BatchUpdateException, and the driver's own exception as both
     * its cause and its next exception. Messages as with
     * {@code logServerErrorDetail=false}.
     */
    static DataIntegrityViolationException collision(String constraint) {
        SQLException driver = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"", "23505");
        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 <unknown> was aborted: "
                + driver.getMessage() + "  Call getNextException to see other errors in the batch.",
                "23505", 0, new int[0], driver);
        batch.setNextException(driver);
        return new DataIntegrityViolationException("could not execute batch; constraint [" + constraint + "]",
                new org.hibernate.exception.ConstraintViolationException("could not execute batch", batch,
                        "insert into booking_items ...", ConstraintKind.UNIQUE, constraint));
    }

    private double collisions(String reference) {
        return registry.get(BookingMetrics.REFERENCE_COLLISION).tag("reference", reference).counter().count();
    }

    private double retries(String outcome) {
        return registry.get(BookingMetrics.CLAIM_RETRY).tag("outcome", outcome).counter().count();
    }

    private void stubCategory(UUID id, String name) {
        when(seats.getCategory(id)).thenReturn(ApiResult.ok(CategoryLookupDTO.builder()
                .seatCategoryId(id).eventId(eventId).name(name).price(new BigDecimal("10.00"))
                .totalSeats(1000).build()));
    }

    private CreateBookingRequestDTO request(UUID... categories) {
        CreateBookingRequestDTO req = new CreateBookingRequestDTO();
        req.setEventId(eventId);
        req.setCustomerName("Rufaro Moyo");
        List<CreateBookingRequestDTO.SeatItemRequest> seatsRequested = new ArrayList<>();
        for (UUID category : categories) {
            CreateBookingRequestDTO.SeatItemRequest s = new CreateBookingRequestDTO.SeatItemRequest();
            s.setCategoryId(category);
            seatsRequested.add(s);
        }
        req.setSeats(seatsRequested);
        return req;
    }
}
