package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.BookingConflictException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins the hold-extension rules payment-service relies on before minting any
 * payment instrument: the booking row is locked first; only a PENDING hold
 * extends (a lapsed one the sweep has not cancelled included); the extension
 * never shortens anything; the payment window is stamped with the same
 * deadline; and a hold past either cap is REFUSED (409, so nothing is minted),
 * never clamped — a clamped hold under a live instrument is a payment that
 * lands with no ticket.
 */
@SuppressWarnings("unchecked")
class BookingServiceExtendHoldTest {

    private BookingRepository bookingRepository;
    private SimpleMeterRegistry registry;
    private BookingService service;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        service = new BookingService(
                bookingRepository,
                mock(BookingItemRepository.class),
                mock(CategoryInventoryRepository.class),
                mock(SeatServiceClient.class),
                mock(ApplicationEventPublisher.class),
                mock(QrCodeGenerator.class),
                mock(ObjectProvider.class),
                mock(PlatformTransactionManager.class));
        registry = new SimpleMeterRegistry();
        service.setMetrics(new BookingMetrics(registry));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** A PENDING booking created {@code minutesAgo} ago whose hold ends at {@code expiresAt}. */
    private Booking pending(int minutesAgo, LocalDateTime expiresAt) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setStatus(Booking.BookingStatus.PENDING);
        b.setCreatedAt(now().minusMinutes(minutesAgo));
        b.setExpiresAt(expiresAt);
        when(bookingRepository.lockById(b.getId())).thenReturn(Optional.of(b));
        return b;
    }

    private double refused(String reason) {
        return registry.get(BookingMetrics.HOLD_EXTENSION_REFUSED).tag("reason", reason).counter().count();
    }

    // ---- the lock, and what extends -------------------------------------------------

    @Test
    void theBookingRowIsLockedFirst_neverReadThroughTheUnlockableFetchJoin() {
        Booking b = pending(2, now().plusMinutes(3));

        service.extendHold(b.getId(), now().plusMinutes(13));

        verify(bookingRepository).lockById(b.getId());
        verify(bookingRepository, never()).findByIdWithItems(any());
    }

    @Test
    void livePendingHold_isExtended_andThePaymentWindowStampedWithTheSameDeadline() {
        Booking b = pending(2, now().plusMinutes(3));
        LocalDateTime until = now().plusMinutes(13);

        BookingResponseDTO dto = service.extendHold(b.getId(), until);

        assertThat(dto.getExpiresAt()).isEqualTo(until);
        assertThat(b.getPaymentOpenUntil()).isEqualTo(until);
        verify(bookingRepository).save(b);
    }

    @Test
    void aLapsedHoldTheSweepHasNotCancelled_isExtended() {
        // Its tickets are still claimed (the sweep releases them only when it
        // commits the cancellation), and confirm honours it too. Refusing it
        // here used to make a buyer who started paying a moment late rebook.
        Booking b = pending(6, now().minusSeconds(30));
        LocalDateTime until = now().plusMinutes(13);

        BookingResponseDTO dto = service.extendHold(b.getId(), until);

        assertThat(dto.getExpiresAt()).isEqualTo(until);
        assertThat(b.getPaymentOpenUntil()).isEqualTo(until);
        assertThat(refused("max_total") + refused("max_per_call")).isZero();
    }

    @Test
    void aHoldTheSweepAlreadyCancelled_isRefusedAsNotPending() {
        Booking b = pending(6, null);
        b.setStatus(Booking.BookingStatus.CANCELLED);
        b.setCancelReason(Booking.CancelReason.EXPIRED);

        assertThatThrownBy(() -> service.extendHold(b.getId(), now().plusMinutes(13)))
                .isInstanceOf(BookingConflictException.class)
                .hasMessage("This booking's seat hold can no longer be extended — it's not in a PENDING state.");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void aConfirmedBooking_isRefusedAsNotPending() {
        Booking b = pending(6, null);
        b.setStatus(Booking.BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> service.extendHold(b.getId(), now().plusMinutes(13)))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("PENDING");
    }

    // ---- never shortens: both stamps are a max -------------------------------------

    @Test
    void neitherStampEverMovesEarlier() {
        LocalDateTime alreadyLong = now().plusMinutes(30);
        Booking b = pending(5, alreadyLong);
        b.setPaymentOpenUntil(alreadyLong);

        BookingResponseDTO dto = service.extendHold(b.getId(), now().plusMinutes(13));

        assertThat(dto.getExpiresAt()).isEqualTo(alreadyLong);
        assertThat(b.getPaymentOpenUntil()).isEqualTo(alreadyLong);
        // Nothing changed — nothing written.
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void aLongerHoldStillGetsItsFirstPaymentWindow() {
        // A card checkout (33 min) left the hold long; a later code (13 min)
        // does not shorten it. The window is a separate max: here the hold
        // already outlives the code, but no payment had been stamped yet.
        LocalDateTime alreadyLong = now().plusMinutes(30);
        Booking b = pending(5, alreadyLong);
        LocalDateTime until = now().plusMinutes(13);

        service.extendHold(b.getId(), until);

        assertThat(b.getExpiresAt()).isEqualTo(alreadyLong);
        assertThat(b.getPaymentOpenUntil()).isEqualTo(until);
        verify(bookingRepository).save(b);
    }

    @Test
    void theWindowFollowsTheLatestDeadline() {
        LocalDateTime earlier = now().plusMinutes(5);
        Booking b = pending(5, earlier);
        b.setPaymentOpenUntil(earlier);
        LocalDateTime later = now().plusMinutes(33);

        service.extendHold(b.getId(), later);

        assertThat(b.getExpiresAt()).isEqualTo(later);
        assertThat(b.getPaymentOpenUntil()).isEqualTo(later);
    }

    // ---- the absolute cap: created_at + 60 min ----------------------------------------

    @Test
    void pastSixtyMinutesFromBooking_isRefused_notClamped() {
        LocalDateTime current = now().plusMinutes(2);
        Booking b = pending(50, current);
        b.setPaymentOpenUntil(current);

        // 50 + 13 = 63 minutes from booking.
        assertThatThrownBy(() -> service.extendHold(b.getId(), now().plusMinutes(13)))
                .isInstanceOf(BookingConflictException.class)
                .hasMessage("Your seat reservation has expired. Please start a new booking and complete payment "
                        + "within 5 minutes.");

        // Not clamped to the cap: nothing moved, nothing written, so the 409
        // reaches payment-service and no instrument is minted.
        assertThat(b.getExpiresAt()).isEqualTo(current);
        assertThat(b.getPaymentOpenUntil()).isEqualTo(current);
        verify(bookingRepository, never()).save(any());
        assertThat(refused("max_total")).isEqualTo(1.0);
        assertThat(refused("max_per_call")).isZero();
    }

    @Test
    void theThirdExtension_pastTheCap_isRefused_andTheFirstTwoStand() {
        // A buyer (or a script) re-minting payment codes on a booking made 20
        // minutes ago. The cap falls 40 minutes from now.
        Booking b = pending(20, now().plusMinutes(1));

        LocalDateTime first = now().plusMinutes(13);   // 33 min from booking
        service.extendHold(b.getId(), first);
        LocalDateTime second = now().plusMinutes(30);  // 50 min from booking
        service.extendHold(b.getId(), second);
        LocalDateTime third = now().plusMinutes(44);   // 64 min from booking

        assertThatThrownBy(() -> service.extendHold(b.getId(), third))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("expired");

        assertThat(b.getExpiresAt()).isEqualTo(second);
        assertThat(b.getPaymentOpenUntil()).isEqualTo(second);
        verify(bookingRepository, times(2)).save(b);
        assertThat(refused("max_total")).isEqualTo(1.0);
    }

    @Test
    void aHoldEndingExactlyAtTheCap_isAllowed() {
        Booking b = pending(30, now().plusMinutes(1));
        LocalDateTime cap = b.getCreatedAt().plusMinutes(60);

        service.extendHold(b.getId(), cap);

        assertThat(b.getExpiresAt()).isEqualTo(cap);
        assertThat(refused("max_total")).isZero();
    }

    @Test
    void aBookingWithNoCreatedAt_failsClosed() {
        Booking b = pending(0, now().plusMinutes(1));
        b.setCreatedAt(null);

        assertThatThrownBy(() -> service.extendHold(b.getId(), now().plusMinutes(13)))
                .isInstanceOf(BookingConflictException.class);
        assertThat(refused("max_total")).isEqualTo(1.0);
    }

    // ---- the per-call bound: now + 45 min --------------------------------------------

    @Test
    void moreThanFortyFiveMinutesFromNow_isRefused_asTheConfigFaultItIs() {
        // Above every rail (card is 33 min): only a rail whose TTL was raised
        // past the bound asks for this, and every one of its payments would be
        // refused, so it is counted for the HoldExtensionRefused alert.
        LocalDateTime current = now().plusMinutes(3);
        Booking b = pending(1, current);

        assertThatThrownBy(() -> service.extendHold(b.getId(), now().plusMinutes(46)))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("expired");

        assertThat(b.getExpiresAt()).isEqualTo(current);
        assertThat(b.getPaymentOpenUntil()).isNull();
        verify(bookingRepository, never()).save(any());
        assertThat(refused("max_per_call")).isEqualTo(1.0);
        assertThat(refused("max_total")).isZero();
    }

    @Test
    void pastBothCaps_isCountedAsThePerCallFault() {
        // A misconfigured rail on a booking that is also old must still show
        // up on the alert, so the per-call bound is checked first.
        Booking b = pending(40, now().plusMinutes(3));

        assertThatThrownBy(() -> service.extendHold(b.getId(), now().plusMinutes(50)))
                .isInstanceOf(BookingConflictException.class);

        assertThat(refused("max_per_call")).isEqualTo(1.0);
        assertThat(refused("max_total")).isZero();
    }

    @Test
    void bothCapsAreConfigurable_inEitherDurationForm() {
        // app.booking.max-hold-extension / max-total-hold, as the yaml binds
        // them: ISO-8601 or Boot's simple form.
        service.setMaxHoldExtension("10m");
        Booking b = pending(1, now().plusMinutes(3));
        assertThatThrownBy(() -> service.extendHold(b.getId(), now().plusMinutes(13)))
                .isInstanceOf(BookingConflictException.class);
        assertThat(refused("max_per_call")).isEqualTo(1.0);

        service.setMaxHoldExtension("PT45M");
        service.setMaxTotalHold("PT10M");
        assertThatThrownBy(() -> service.extendHold(b.getId(), now().plusMinutes(13)))
                .isInstanceOf(BookingConflictException.class);
        assertThat(refused("max_total")).isEqualTo(1.0);
    }

    @Test
    void bothRefusalSeriesAreRegisteredAtZero() {
        assertThat(refused("max_total")).isZero();
        assertThat(refused("max_per_call")).isZero();
    }

    // ---- refusals that were always there ------------------------------------------------

    @Test
    void unknownBooking_isNotFound() {
        UUID id = UUID.randomUUID();
        when(bookingRepository.lockById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.extendHold(id, now().plusMinutes(13)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aDeadlineInThePast_isABadRequest() {
        Booking b = pending(2, now().plusMinutes(3));

        assertThatThrownBy(() -> service.extendHold(b.getId(), now().minusMinutes(1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("holdUntil must be in the future.");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void nullDeadline_isRejectedOutright() {
        assertThatThrownBy(() -> service.extendHold(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- the sales end (event-service V22) -------------------------------------------

    /** A service whose event-service answers {@code status} (null = could not be asked) for every event. */
    private BookingService withSalesWindow(String status, LocalDateTime salesEnd) {
        com.innbucks.bookingservice.client.EventServiceClient events =
                mock(com.innbucks.bookingservice.client.EventServiceClient.class);
        if (status == null) {
            when(events.salesWindow(any(), any())).thenReturn(null);
        } else {
            when(events.salesWindow(any(), any())).thenAnswer(inv -> com.innbucks.bookingservice.dto.ApiResult.ok(
                    com.innbucks.bookingservice.dto.EventSalesWindowDTO.builder()
                            .eventId(inv.getArgument(0)).status(status).salesEndDateTime(salesEnd).build()));
        }
        ObjectProvider<com.innbucks.bookingservice.client.EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(events);
        BookingService s = new BookingService(
                bookingRepository,
                mock(BookingItemRepository.class),
                mock(CategoryInventoryRepository.class),
                mock(SeatServiceClient.class),
                mock(ApplicationEventPublisher.class),
                mock(QrCodeGenerator.class),
                provider,
                mock(PlatformTransactionManager.class));
        s.setMetrics(new BookingMetrics(registry));
        return s;
    }

    /** {@link #pending} for a booking of an event, readable unlocked too (the sales-window read). */
    private Booking pendingForAnEvent(int minutesAgo, LocalDateTime expiresAt) {
        Booking b = pending(minutesAgo, expiresAt);
        b.setEventId(UUID.randomUUID());
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    void afterTheSalesEnd_noPaymentMayStart_409SalesClosed_andNothingIsWritten() {
        LocalDateTime closedAt = now().minusMinutes(1);
        BookingService closed = withSalesWindow("SALES_CLOSED", closedAt);
        Booking b = pendingForAnEvent(2, now().plusMinutes(3));

        assertThatThrownBy(() -> closed.extendHold(b.getId(), now().plusMinutes(13)))
                .isInstanceOfSatisfying(com.innbucks.bookingservice.exception.ApiProblemException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("SALES_CLOSED");
                    assertThat(e.getMessage()).isEqualTo("Ticket sales for this event have closed.");
                    assertThat(e.getExtra()).containsEntry("salesEndDateTime", closedAt);
                });

        verify(bookingRepository, never()).lockById(any());
        verify(bookingRepository, never()).save(any());
        assertThat(b.getPaymentOpenUntil()).isNull();
        assertThat(refused("sales_closed")).isEqualTo(1.0);
    }

    @Test
    void afterTheEventEnds_noPaymentMayStart_409EventEnded() {
        BookingService ended = withSalesWindow("ENDED", null);
        Booking b = pendingForAnEvent(2, now().plusMinutes(3));

        assertThatThrownBy(() -> ended.extendHold(b.getId(), now().plusMinutes(13)))
                .isInstanceOfSatisfying(com.innbucks.bookingservice.exception.ApiProblemException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("EVENT_ENDED");
                    assertThat(e.getMessage()).isEqualTo("This event has ended, so tickets are no longer on sale.");
                });
        verify(bookingRepository, never()).save(any());
        assertThat(refused("event_ended")).isEqualTo(1.0);
    }

    @Test
    void whileOnSale_theHoldExtends_asBefore() {
        BookingService onSale = withSalesWindow("ON_SALE", now().plusHours(1));
        Booking b = pendingForAnEvent(2, now().plusMinutes(3));
        LocalDateTime until = now().plusMinutes(13);

        BookingResponseDTO dto = onSale.extendHold(b.getId(), until);

        assertThat(dto.getExpiresAt()).isEqualTo(until);
        assertThat(b.getPaymentOpenUntil()).isEqualTo(until);
    }

    @Test
    void whenEventServiceCannotAnswer_theHoldStillExtends_andItIsCounted() {
        BookingService unanswered = withSalesWindow(null, null);
        Booking b = pendingForAnEvent(2, now().plusMinutes(3));
        LocalDateTime until = now().plusMinutes(13);

        BookingResponseDTO dto = unanswered.extendHold(b.getId(), until);

        assertThat(dto.getExpiresAt()).isEqualTo(until);
        assertThat(registry.get(BookingMetrics.HOLD_SALES_WINDOW_UNCHECKED).counter().count()).isEqualTo(1.0);
    }

    @Test
    void aBookingThatIsNotPending_isNeverAskedAbout_andKeepsItsOwnRefusal() {
        BookingService closed = withSalesWindow("SALES_CLOSED", now().minusMinutes(1));
        Booking b = pendingForAnEvent(6, null);
        b.setStatus(Booking.BookingStatus.CONFIRMED);

        assertThatThrownBy(() -> closed.extendHold(b.getId(), now().plusMinutes(13)))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("PENDING");
        assertThat(refused("sales_closed")).isZero();
    }
}
