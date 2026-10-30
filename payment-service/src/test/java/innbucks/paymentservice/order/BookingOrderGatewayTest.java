package innbucks.paymentservice.order;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.EventServiceClient;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import innbucks.paymentservice.service.RailPayableWindows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the booking adapter as the SINGLE major→minor conversion point for
 * the booking product (decimal dollars → cents, sub-cent refused, never
 * rounded), plus the snapshot's settlement tag / narration / payer sourcing,
 * the ConfirmOutcome mapping of booking-service's confirm statuses, and the
 * seat hold sized by the RAIL's last payable instant + the safety margin.
 */
class BookingOrderGatewayTest {

    private BookingServiceClient bookings;
    private EventServiceClient events;
    private BookingOrderGateway gateway;

    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bookings = mock(BookingServiceClient.class);
        events = mock(EventServiceClient.class);
        when(events.getSettlementInfo(any())).thenReturn(Optional.empty());
        gateway = new BookingOrderGateway(bookings, events, "USD");
    }

    @Test
    void type_isBooking() {
        assertEquals(OrderType.BOOKING, gateway.type());
    }

    @Test
    void fetch_convertsDecimalDollarsToCents_exactly() {
        when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("50.00"), "currency", "USD",
                "phoneNumber", "+263770000001"));

        OrderSnapshot snapshot = gateway.fetch(bookingId.toString());

        assertEquals(5000L, snapshot.amountCents());
        assertEquals("USD", snapshot.currency());
        assertEquals("+263770000001", snapshot.payerMsisdn());
        assertTrue(snapshot.payable(), "the booking flow gates liveness via extend-hold, not a payable flag");
        assertEquals(bookingId.toString(), snapshot.orderRef());
    }

    @Test
    void fetch_subCentPrecision_isRefused422_neverRounded() {
        when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("50.005")));

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.fetch(bookingId.toString()));
        assertEquals(422, ex.getStatusCode());
    }

    @Test
    void fetch_missingOrNonPositiveAmount_isRefused422() {
        when(bookings.getBooking(bookingId)).thenReturn(Map.of("currency", "USD"));
        assertEquals(422, assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.fetch(bookingId.toString())).getStatusCode());

        when(bookings.getBooking(bookingId)).thenReturn(Map.of("totalAmount", BigDecimal.ZERO));
        assertEquals(422, assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.fetch(bookingId.toString())).getStatusCode());
    }

    @Test
    void fetch_missingCurrency_fallsBackToTheCellCurrency() {
        gateway = new BookingOrderGateway(bookings, events, "KES");
        when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("40.00")));

        assertEquals("KES", gateway.fetch(bookingId.toString()).currency());
    }

    @Test
    void fetch_settlementTagAndNarration_fromTheEventLookup() {
        UUID eventId = UUID.randomUUID();
        when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("50.00"), "eventId", eventId.toString()));
        when(events.getSettlementInfo(eventId)).thenReturn(Optional.of(
                new EventServiceClient.EventSettlementInfo("PINKRUN26", "Pink Fun Run")));

        OrderSnapshot snapshot = gateway.fetch(bookingId.toString());

        assertEquals("PINKRUN26", snapshot.settlementTag());
        assertTrue(snapshot.narration().contains("Pink Fun Run"));
        assertTrue(snapshot.narration().contains(bookingId.toString().substring(0, 8)));
    }

    @Test
    void fetch_eventLookupMiss_degradesToEventIdTag_neverBlocks() {
        UUID eventId = UUID.fromString("20c96393-8ac8-480a-93d0-ef89981c53e0");
        when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("50.00"), "eventId", eventId.toString()));

        OrderSnapshot snapshot = gateway.fetch(bookingId.toString());

        assertEquals("20C96393", snapshot.settlementTag());
        assertTrue(snapshot.narration().startsWith("InnBucks ticket booking"));
    }

    @Test
    void fetch_nonUuidRef_isRefused400() {
        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.fetch("MKT-NOT-A-BOOKING"));
        assertEquals(400, ex.getStatusCode());
        verifyNoInteractions(bookings);
    }

    @Test
    void extendHold_holdsExactlyThePayableDeadlinePlusTheSafetyMargin() {
        Instant payableUntil = Instant.parse("2026-10-13T10:30:00Z");

        gateway.extendHold(bookingId.toString(), payableUntil);

        verify(bookings).extendHold(bookingId, payableUntil.plus(OrderGateway.HOLD_SAFETY_MARGIN));
        assertEquals(Duration.ofMinutes(3), OrderGateway.HOLD_SAFETY_MARGIN);
    }

    /**
     * The seat hold per rail. Each payable window is read from the rail's
     * deployed TTL default + its own grace (not restated here), and the rail
     * tests pin that each rail sends exactly that deadline. The holds are the
     * published numbers — plan D2 and §4 of Payments-Frontend-Integration.md —
     * so a TTL default changed without them fails here. A code-sized 13
     * minutes for every rail left a card paid after minute 13 of its
     * 30-minute checkout with no ticket.
     */
    @Test
    void extendHold_perRail_holdOutlivesThePayableDeadlineByThreeMinutes() {
        assertHoldFor(RailPayableWindows.innbucksCode(), Duration.ofMinutes(13)); // 10m code TTL
        assertHoldFor(RailPayableWindows.ecocash(), Duration.ofMinutes(10));      // 5m prompt + 2m grace
        assertHoldFor(RailPayableWindows.card(), Duration.ofMinutes(33));         // 28m checkout + 2m grace
    }

    private void assertHoldFor(Duration payableWindow, Duration expectedHold) {
        reset(bookings);
        Instant before = Instant.now();
        gateway.extendHold(bookingId.toString(), Instant.now().plus(payableWindow));
        Instant after = Instant.now();

        org.mockito.ArgumentCaptor<Instant> until = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(bookings).extendHold(eq(bookingId), until.capture());
        assertFalse(until.getValue().isBefore(before.plus(expectedHold)),
                "a " + payableWindow + " instrument needs a hold of at least " + expectedHold);
        assertFalse(until.getValue().isAfter(after.plus(expectedHold)),
                "the hold is the rail's deadline + the margin, nothing more");
    }

    @Test
    void extendHold_refusal_mapsTo409_customerRebooksWithZeroMoneyMoved() {
        doThrow(new BookingServiceClient.BookingConfirmationException("Seat hold expired", 409))
                .when(bookings).extendHold(eq(bookingId), any(Instant.class));

        assertEquals(409, assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.extendHold(bookingId.toString(), Instant.now().plus(Duration.ofMinutes(10))))
                .getStatusCode());
    }

    @Test
    void extendHold_unreachable_mapsTo503() {
        doThrow(new BookingServiceClient.BookingConfirmationException("unreachable", 503))
                .when(bookings).extendHold(eq(bookingId), any(Instant.class));

        assertEquals(503, assertThrows(InvalidPaymentRequestException.class,
                () -> gateway.extendHold(bookingId.toString(), Instant.now().plus(Duration.ofMinutes(10))))
                .getStatusCode());
    }

    @Test
    void confirm_2xx_isConfirmed_withTheConfirmationNumber() {
        when(bookings.confirmBooking(eq(bookingId), any())).thenReturn(Map.of("confirmationNumber", "INN-CONF-1"));

        ConfirmOutcome outcome = gateway.confirm(bookingId.toString(), "TKZ-X-1", 5000L);

        assertTrue(outcome.succeeded());
        assertEquals("INN-CONF-1", outcome.confirmationNumber());
    }

    @Test
    void confirm_4xxRefusal_isRejected_neverThrown() {
        when(bookings.confirmBooking(eq(bookingId), any()))
                .thenThrow(new BookingServiceClient.BookingConfirmationException("hold expired", 409));

        ConfirmOutcome outcome = gateway.confirm(bookingId.toString(), "TKZ-X-1", 5000L);

        assertEquals(ConfirmOutcome.Result.REJECTED, outcome.result());
        assertEquals("hold expired", outcome.reason());
    }

    @Test
    void confirm_unreachable_isUnreachable_soTheCallerParksNotFails() {
        when(bookings.confirmBooking(eq(bookingId), any()))
                .thenThrow(new BookingServiceClient.BookingConfirmationException(
                        "Unable to reach booking-service to confirm the booking", 503));

        ConfirmOutcome outcome = gateway.confirm(bookingId.toString(), "TKZ-X-1", 5000L);

        assertEquals(ConfirmOutcome.Result.UNREACHABLE, outcome.result());
        assertFalse(outcome.succeeded());
    }
}
