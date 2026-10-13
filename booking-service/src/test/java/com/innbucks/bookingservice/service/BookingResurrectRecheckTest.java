package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.BookingConflictException;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 of a resurrect re-reads the booking under its row lock, and that
 * re-check is the ONLY thing between two confirms of one cancelled booking and
 * a double claim. Two confirms of one payment are ordinary — the first confirm
 * after the payment races payment-service's one-minute retry, the EcoCash
 * notify races its poller — and both can pass phase 1 while the booking is
 * still CANCELLED, then wait for each other in phase 3. Here the second phase-3
 * read returns what the other confirm (or an admin after it) committed in the
 * meantime:
 * <ul>
 *   <li>CONFIRMED — the other confirm resurrected it: the ordinary replay, with
 *       no claim, no save and no {@code BookingConfirmed} (which would send the
 *       tickets and decrement event-service's availability a second time);</li>
 *   <li>CANCELLED by an admin reversal — resurrected, then refunded: refused
 *       (409), claiming nothing, so a stale confirm cannot bring a refunded
 *       booking back.</li>
 * </ul>
 * The same two races against real Postgres are in
 * {@code BookingPaymentWindowPostgresIT}.
 */
@SuppressWarnings("unchecked")
class BookingResurrectRecheckTest {

    private static final UUID CATEGORY = UUID.randomUUID();

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final CategoryInventoryRepository inventory = mock(CategoryInventoryRepository.class);
    private final SeatServiceClient seatClient = mock(SeatServiceClient.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UUID bookingId = UUID.randomUUID();
    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(bookingRepository, mock(BookingItemRepository.class), inventory,
                seatClient, publisher, new QrCodeGenerator(), mock(ObjectProvider.class),
                mock(PlatformTransactionManager.class));
        service.setMetrics(new BookingMetrics(registry));
        when(seatClient.getCategory(CATEGORY)).thenReturn(ApiResult.ok(CategoryLookupDTO.builder()
                .seatCategoryId(CATEGORY).name("General").totalSeats(10).build()));
        when(inventory.tryClaim(any(), anyInt())).thenReturn(1);
    }

    @Test
    void phaseThreeFindsItConfirmedByAnotherConfirm_isTheReplay_andClaimsNothing() {
        // Phase 1 read CANCELLED(EXPIRED); by phase 3 the other confirm has
        // re-claimed and confirmed it.
        when(bookingRepository.lockById(bookingId)).thenReturn(
                Optional.of(booking(Booking.BookingStatus.CANCELLED, Booking.CancelReason.EXPIRED)),
                Optional.of(booking(Booking.BookingStatus.CONFIRMED, null)));

        BookingResponseDTO response = service.confirmBooking(bookingId);

        assertThat(response.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        verify(bookingRepository, times(2)).lockById(bookingId);
        verify(seatClient).getCategory(CATEGORY);
        verify(inventory, never()).tryClaim(any(), anyInt());
        verify(bookingRepository, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
        assertThat(resurrected("succeeded")).as("the other confirm counted it").isZero();
        assertThat(resurrected("failed")).as("a replay is not a failure").isZero();
    }

    @Test
    void phaseThreeFindsItReversedSinceTheOtherConfirmResurrectedIt_isRefused_andClaimsNothing() {
        // Phase 1 read CANCELLED(EXPIRED); meanwhile another confirm resurrected
        // it and an admin reversed it (refunded). Still CANCELLED, so only the
        // re-check stands between this stale confirm and a refunded booking
        // coming back with its tickets claimed again.
        when(bookingRepository.lockById(bookingId)).thenReturn(
                Optional.of(booking(Booking.BookingStatus.CANCELLED, Booking.CancelReason.EXPIRED)),
                Optional.of(booking(Booking.BookingStatus.CANCELLED, Booking.CancelReason.ADMIN_REVERSED)));

        assertThatThrownBy(() -> service.confirmBooking(bookingId))
                .isInstanceOf(BookingConflictException.class)
                .hasMessage(BookingService.NOT_CONFIRMABLE_MESSAGE);

        verify(bookingRepository, times(2)).lockById(bookingId);
        verify(inventory, never()).tryClaim(any(), anyInt());
        verify(bookingRepository, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
        assertThat(resurrected("failed")).isEqualTo(1);
        assertThat(resurrected("succeeded")).isZero();
    }

    @Test
    void phaseThreeFindsItUnchanged_claimsOnce_andConfirms() {
        // The control for the two above: the same mocks, nothing changed in
        // between, so the claim and the publish do happen.
        when(bookingRepository.lockById(bookingId)).thenReturn(
                Optional.of(booking(Booking.BookingStatus.CANCELLED, Booking.CancelReason.EXPIRED)),
                Optional.of(booking(Booking.BookingStatus.CANCELLED, Booking.CancelReason.EXPIRED)));

        BookingResponseDTO response = service.confirmBooking(bookingId);

        assertThat(response.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        verify(inventory).tryClaim(CATEGORY, 2);
        verify(publisher).publishEvent(any(Object.class));
        assertThat(resurrected("succeeded")).isEqualTo(1);
    }

    private double resurrected(String outcome) {
        return registry.get(BookingMetrics.CONFIRM_RESURRECTED)
                .tag("reason", "expired").tag("outcome", outcome).counter().count();
    }

    /** A fresh entity per read, as each transaction's lockById returns one: two tickets in CATEGORY. */
    private Booking booking(Booking.BookingStatus status, Booking.CancelReason reason) {
        Booking booking = Booking.builder()
                .id(bookingId)
                .version(3L)
                .userEmail("buyer@example.com")
                .eventId(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"))
                .confirmationNumber("INN-RECHECK")
                .status(status)
                .cancelReason(reason)
                .totalAmount(new BigDecimal("20.00"))
                .cashAmount(status == Booking.BookingStatus.CONFIRMED ? new BigDecimal("20.00") : null)
                .items(new ArrayList<>())
                .build();
        for (int seat = 1; seat <= 2; seat++) {
            booking.getItems().add(BookingItem.builder().booking(booking).categoryId(CATEGORY)
                    .categoryName("General").rowLabel("GA").seatNumber(seat)
                    .ticketNumber("T-RECHECK-" + seat).build());
        }
        return booking;
    }
}
