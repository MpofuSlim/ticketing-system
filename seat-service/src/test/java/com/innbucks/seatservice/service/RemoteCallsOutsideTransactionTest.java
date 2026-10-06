package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.CategoryBookingDTO;
import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.EventAnalyticsDTO;
import com.innbucks.seatservice.dto.EventLookupDTO;
import com.innbucks.seatservice.dto.UpdateCategoryRequestDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.exception.NotFoundException;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import com.innbucks.seatservice.testsupport.RecordingTransactionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.innbucks.seatservice.testsupport.RecordingTransactionManager.inTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins WHERE seat analytics and the category update make their event-service
 * and booking-service calls: never while a transaction is open. Each client's
 * answer records {@link RecordingTransactionManager#inTransaction()} at the
 * moment it is called, and each repository call records it too, so a
 * regression that puts the method-level {@code @Transactional} back fails here
 * rather than as a pool held hostage by a slow sibling.
 *
 * <p>Real Postgres behaviour is pinned by {@code RemoteCallsOutsideTransactionsPostgresIT}.
 */
class RemoteCallsOutsideTransactionTest {

    private final RecordingTransactionManager txManager = new RecordingTransactionManager();
    private final List<String> calls = new ArrayList<>();

    private final SeatCategoryRepository categoryRepo = mock(SeatCategoryRepository.class);
    private final SeatRepository seatRepo = mock(SeatRepository.class);
    private final BookingServiceClient bookingClient = mock(BookingServiceClient.class);
    private final EventServiceClient eventClient = mock(EventServiceClient.class);

    private final UUID eventId = UUID.randomUUID();
    private final UUID organizer = UUID.randomUUID();

    // ---- analytics -----------------------------------------------------------

    @Test
    void analytics_asksEventServiceAndBookingServiceFirst_thenReadsInOneReadOnlyTransaction() {
        SeatCategory vip = category(UUID.randomUUID());
        ownedEvent();
        when(bookingClient.fetchBookingsByEvent(eq(eventId), any())).thenAnswer(inv -> {
            record("booking-service");
            return Optional.of(List.of(CategoryBookingDTO.builder()
                    .bookingId(UUID.randomUUID()).categoryId(vip.getId()).seatId(UUID.randomUUID())
                    .status(CategoryBookingDTO.BookingStatus.CONFIRMED)
                    .priceAtBooking(new BigDecimal("50.00")).build()));
        });
        when(categoryRepo.findByEventIdAndDeletedFalse(eventId)).thenAnswer(inv -> {
            record("categories");
            return List.of(vip);
        });

        EventAnalyticsDTO result = analytics().getEventAnalytics(eventId, organizer, "o@example.com",
                false, 0, 20, "Bearer x");

        assertThat(result.isBookingServiceReachable()).isTrue();
        assertThat(result.getTotals().getPaidBookings()).isEqualTo(1);
        assertThat(calls).containsExactly("event-service|false", "booking-service|false", "categories|true");
        assertThat(txManager.readOnlyFlags()).containsExactly(true);
    }

    @Test
    void analytics_bookingServiceDown_readsTheSeatFallbackInsideTheSameReadTransaction() {
        SeatCategory vip = category(UUID.randomUUID());
        when(bookingClient.fetchBookingsByEvent(eq(eventId), any())).thenAnswer(inv -> {
            record("booking-service");
            return Optional.empty();
        });
        when(categoryRepo.findByEventIdAndDeletedFalse(eventId)).thenAnswer(inv -> {
            record("categories");
            return List.of(vip);
        });
        when(seatRepo.findByCategoryId(vip.getId())).thenAnswer(inv -> {
            record("seats");
            return List.of(Seat.builder().status(Seat.SeatStatus.AVAILABLE).build());
        });

        EventAnalyticsDTO result = analytics().getEventAnalytics(eventId, null, "admin@example.com",
                true, 0, 20, "Bearer x");

        assertThat(result.isBookingServiceReachable()).isFalse();
        assertThat(result.getTotals().getAvailableSeats()).isEqualTo(1);
        assertThat(calls).containsExactly("booking-service|false", "categories|true", "seats|true");
        assertThat(txManager.readOnlyFlags()).containsExactly(true);
    }

    @Test
    void analytics_ownershipRefusal_stillComesFirst_andReadsNothing() {
        when(eventClient.fetchEvent(eq(eventId), any())).thenReturn(Optional.of(
                EventLookupDTO.builder().eventId(eventId).tenantUserUuid(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> analytics().getEventAnalytics(eventId, organizer, "o@example.com",
                false, 0, 20, "Bearer x")).isInstanceOf(AccessDeniedException.class);

        verify(bookingClient, never()).fetchBookingsByEvent(any(), any());
        verify(categoryRepo, never()).findByEventIdAndDeletedFalse(any());
        assertThat(txManager.outcomes()).isEmpty();
    }

    // ---- category update -----------------------------------------------------

    @Test
    void organizerUpdate_checksOwnershipAndFetchesLiveCountsWithNoTransaction() {
        UUID id = UUID.randomUUID();
        SeatCategory vip = category(id);
        ownedEvent();
        when(categoryRepo.findById(id)).thenAnswer(inv -> {
            record("read");
            return Optional.of(vip);
        });
        when(categoryRepo.save(any())).thenAnswer(inv -> {
            record("save");
            return inv.getArgument(0);
        });
        when(seatRepo.countSections(anyCollection())).thenAnswer(inv -> {
            record("sections");
            return List.of();
        });
        when(bookingClient.fetchActiveCountsByCategories(anyCollection())).thenAnswer(inv -> {
            record("booking-service");
            return Optional.of(Map.of(id, 3L));
        });

        CreateCategoryResponseDTO result = categories().updateCategory(id, update("VVIP", "120.00"),
                organizer, "o@example.com", false, "Bearer x");

        assertThat(result.getName()).isEqualTo("VVIP");
        assertThat(result.getAvailableSeats()).isEqualTo(7);
        assertThat(calls).containsExactly(
                "read|true", "event-service|false", "read|true", "save|true", "sections|true",
                "booking-service|false");
        assertThat(txManager.readOnlyFlags()).containsExactly(true, false);
    }

    @Test
    void adminUpdate_skipsTheOwnershipRead_andStillFetchesCountsAfterTheCommit() {
        UUID id = UUID.randomUUID();
        when(categoryRepo.findById(id)).thenAnswer(inv -> {
            record("read");
            return Optional.of(category(id));
        });
        when(categoryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookingClient.fetchActiveCountsByCategories(anyCollection())).thenAnswer(inv -> {
            record("booking-service");
            return Optional.empty();
        });

        CreateCategoryResponseDTO result = categories().updateCategory(id, update("VVIP", "120.00"));

        // booking-service down: the stored mirror, exactly as before.
        assertThat(result.getAvailableSeats()).isEqualTo(10);
        assertThat(calls).containsExactly("read|true", "booking-service|false");
        assertThat(txManager.readOnlyFlags()).containsExactly(false);
    }

    @Test
    void updateOfACategoryDeletedDuringTheOwnershipCheck_isStillA404_andWritesNothing() {
        UUID id = UUID.randomUUID();
        SeatCategory live = category(id);
        SeatCategory deleted = category(id);
        deleted.setDeleted(true);
        ownedEvent();
        when(categoryRepo.findById(id)).thenReturn(Optional.of(live)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> categories().updateCategory(id, update("VVIP", "120.00"),
                organizer, "o@example.com", false, "Bearer x")).isInstanceOf(NotFoundException.class);

        verify(categoryRepo, never()).save(any());
        verify(bookingClient, never()).fetchActiveCountsByCategories(anyCollection());
    }

    // ---- shape -----------------------------------------------------------------

    @Test
    void analyticsAndUpdate_carryNoMethodLevelTransaction_theGuardedWritesKeepTheirs() throws Exception {
        for (Method m : new Method[] {
                SeatCategoryAnalyticsService.class.getMethod("getEventAnalytics", UUID.class, UUID.class,
                        String.class, boolean.class, int.class, int.class, String.class),
                SeatCategoryService.class.getMethod("updateCategory", UUID.class, UpdateCategoryRequestDTO.class),
                SeatCategoryService.class.getMethod("updateCategory", UUID.class, UpdateCategoryRequestDTO.class,
                        UUID.class, String.class, boolean.class, String.class)}) {
            assertThat(m.isAnnotationPresent(Transactional.class))
                    .as("%s.%s must not be @Transactional — it makes a remote call",
                            m.getDeclaringClass().getSimpleName(), m.getName())
                    .isFalse();
            assertThat(m.getDeclaringClass().isAnnotationPresent(Transactional.class)).isFalse();
        }
        // Deliberately unchanged: the oversell guard and the delete guard decide
        // their write, and the oversell guard sums the live categories in the
        // same transaction that inserts the new one. See SeatCategoryService.
        assertThat(SeatCategoryService.class.getMethod("createCategory", CreateCategoryRequestDTO.class,
                UUID.class, String.class, boolean.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(SeatCategoryService.class.getMethod("deleteCategory", UUID.class,
                UUID.class, String.class, boolean.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    // ---- helpers -------------------------------------------------------------

    private void record(String what) {
        calls.add(what + "|" + inTransaction());
    }

    private void ownedEvent() {
        when(eventClient.fetchEvent(eq(eventId), any())).thenAnswer(inv -> {
            record("event-service");
            return Optional.of(EventLookupDTO.builder().eventId(eventId).tenantUserUuid(organizer).build());
        });
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<EventServiceClient> eventProvider() {
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(eventClient);
        return provider;
    }

    private SeatCategoryAnalyticsService analytics() {
        SeatCategoryAnalyticsService service =
                new SeatCategoryAnalyticsService(categoryRepo, seatRepo, bookingClient, eventProvider());
        service.setTransactionManager(txManager);
        return service;
    }

    private SeatCategoryService categories() {
        SeatCategoryService service = new SeatCategoryService(categoryRepo, seatRepo, bookingClient, eventProvider());
        service.setTransactionManager(txManager);
        return service;
    }

    private SeatCategory category(UUID id) {
        return SeatCategory.builder()
                .id(id)
                .eventId(eventId)
                .name("VIP")
                .price(new BigDecimal("50.00"))
                .totalSeats(10)
                .availableSeats(10)
                .deleted(false)
                .build();
    }

    private static UpdateCategoryRequestDTO update(String name, String price) {
        UpdateCategoryRequestDTO req = new UpdateCategoryRequestDTO();
        req.setName(name);
        req.setPrice(new BigDecimal(price));
        return req;
    }
}
