package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.CategoryBookingDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.EventAnalyticsDTO;
import com.innbucks.seatservice.dto.EventLookupDTO;
import com.innbucks.seatservice.dto.UpdateCategoryRequestDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import com.innbucks.seatservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End to end on real Postgres: seat analytics and the category update make
 * their event-service / booking-service calls with no transaction open and no
 * pooled connection bound to the calling thread.
 *
 * <p>Each client is a Mockito bean whose answer RECORDS what the calling
 * thread looked like at the moment of the call (transaction active? any
 * resource — the EntityManager / JDBC connection holder — bound?). For the
 * update, the live-count call also proves from ANOTHER connection that the
 * edited row is already committed and can be locked {@code FOR UPDATE NOWAIT}.
 */
class RemoteCallsOutsideTransactionsPostgresIT extends PostgresIntegrationTestBase {

    @MockitoBean private BookingServiceClient bookingServiceClient;
    @MockitoBean private EventServiceClient eventServiceClient;

    @Autowired private SeatCategoryAnalyticsService analyticsService;
    @Autowired private SeatCategoryService categoryService;
    @Autowired private SeatCategoryRepository categoryRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private TransactionTemplate tx;
    private final UUID organizer = UUID.randomUUID();
    /** One line per remote call: "what|txActive|resourcesBound". */
    private final Queue<String> calls = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM seats");
        jdbc.update("DELETE FROM seat_categories");
    }

    @Test
    void organizerAnalytics_asksEventServiceAndBookingServiceWithNoConnectionHeld() {
        UUID eventId = UUID.randomUUID();
        UUID categoryId = seedCategory(eventId);
        ownedBy(eventId, organizer);
        when(bookingServiceClient.fetchBookingsByEvent(eq(eventId), any())).thenAnswer(inv -> {
            record("booking-service");
            return Optional.of(List.of(CategoryBookingDTO.builder()
                    .bookingId(UUID.randomUUID()).categoryId(categoryId).seatId(UUID.randomUUID())
                    .status(CategoryBookingDTO.BookingStatus.CONFIRMED)
                    .priceAtBooking(new BigDecimal("10.00")).build()));
        });

        EventAnalyticsDTO result = analyticsService.getEventAnalytics(eventId, organizer, "o@example.com",
                false, 0, 20, "Bearer x");

        assertThat(result.getCategoryCount()).isEqualTo(1);
        assertThat(result.isBookingServiceReachable()).isTrue();
        assertThat(result.getTotals().getBookedSeats()).isEqualTo(1);
        assertAllCallsOffTransaction(2);
    }

    @Test
    void analyticsFallback_readsTheSeatsTable_afterBookingServiceFailedWithNoConnectionHeld() {
        UUID eventId = UUID.randomUUID();
        seedCategory(eventId);
        when(bookingServiceClient.fetchBookingsByEvent(eq(eventId), any())).thenAnswer(inv -> {
            record("booking-service");
            return Optional.empty();
        });

        EventAnalyticsDTO result = analyticsService.getEventAnalytics(eventId, null, "admin@example.com",
                true, 0, 20, "Bearer x");

        assertThat(result.isBookingServiceReachable()).isFalse();
        assertThat(result.getTotals().getAvailableSeats()).isEqualTo(4);
        assertAllCallsOffTransaction(1);
    }

    @Test
    void organizerUpdate_commitsBeforeTheLiveCountCall_andHoldsNoConnectionDuringEitherCall() {
        UUID eventId = UUID.randomUUID();
        UUID categoryId = seedCategory(eventId);
        ownedBy(eventId, organizer);
        Queue<String> seenFromAnotherConnection = new ConcurrentLinkedQueue<>();
        when(bookingServiceClient.fetchActiveCountsByCategories(anyCollection())).thenAnswer(inv -> {
            record("booking-service");
            seenFromAnotherConnection.add(lockAndRead(categoryId));
            return Optional.of(Map.of(categoryId, 1L));
        });
        UpdateCategoryRequestDTO request = new UpdateCategoryRequestDTO();
        request.setName("Renamed");
        request.setDescription("d");
        request.setPrice(new BigDecimal("12.50"));

        CreateCategoryResponseDTO result = categoryService.updateCategory(categoryId, request,
                organizer, "o@example.com", false, "Bearer x");

        assertThat(result.getName()).isEqualTo("Renamed");
        assertThat(result.getAvailableSeats()).isEqualTo(3);
        assertThat(result.getSections()).singleElement()
                .satisfies(s -> assertThat(s.getSeatCount()).isEqualTo(4));
        assertAllCallsOffTransaction(2);
        // Committed (visible elsewhere) and unlocked while booking-service was asked.
        assertThat(seenFromAnotherConnection).containsExactly("Renamed");
    }

    // ---- helpers -------------------------------------------------------------

    private void ownedBy(UUID eventId, UUID owner) {
        when(eventServiceClient.fetchEvent(eq(eventId), any())).thenAnswer(inv -> {
            record("event-service");
            return Optional.of(EventLookupDTO.builder().eventId(eventId).tenantUserUuid(owner)
                    .totalCapacity(100).build());
        });
    }

    private void record(String what) {
        calls.add(what
                + "|" + TransactionSynchronizationManager.isActualTransactionActive()
                + "|" + !TransactionSynchronizationManager.getResourceMap().isEmpty());
    }

    private void assertAllCallsOffTransaction(int expected) {
        assertThat(calls).hasSize(expected);
        assertThat(calls).allSatisfy(c -> {
            String[] parts = c.split("\\|");
            assertThat(parts[1]).as("transaction active during %s", c).isEqualTo("false");
            assertThat(parts[2]).as("resource bound during %s", c).isEqualTo("false");
        });
    }

    /**
     * From a different connection (REQUIRES_NEW, whatever the caller holds):
     * lock the category row with NOWAIT and read its name; "LOCKED" when the
     * lock is refused.
     */
    private String lockAndRead(UUID categoryId) {
        TransactionTemplate other = new TransactionTemplate(txManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return other.execute(st -> jdbc.queryForObject(
                    "SELECT name FROM seat_categories WHERE id = ? FOR UPDATE NOWAIT", String.class, categoryId));
        } catch (DataAccessException lockNotAvailable) {
            return "LOCKED";
        }
    }

    private UUID seedCategory(UUID eventId) {
        return tx.execute(s -> {
            SeatCategory category = categoryRepository.save(SeatCategory.builder()
                    .eventId(eventId)
                    .name("General")
                    .price(new BigDecimal("10.00"))
                    .totalSeats(4)
                    .availableSeats(4)
                    .deleted(false)
                    .build());
            List<Seat> seats = new ArrayList<>();
            for (int n = 1; n <= 4; n++) {
                seats.add(Seat.builder()
                        .category(category)
                        .sectionLabel("A")
                        .seatNumber(n)
                        .status(Seat.SeatStatus.AVAILABLE)
                        .build());
            }
            seatRepository.saveAll(seats);
            return category.getId();
        });
    }
}
