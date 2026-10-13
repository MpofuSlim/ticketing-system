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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The claim transaction's canonical lock order and its one retry. The real
 * deadlock and the real 40P01 through the JDBC stack are pinned against
 * Postgres by {@code CategoryInventoryConcurrencyIT} and
 * {@code BookingClaimRetryPostgresIT}; this pins the decisions.
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
