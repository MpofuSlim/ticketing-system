package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.exception.SeatServiceUnavailableException;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H2's payment window against real Postgres (V25, its CHECK, and the V5
 * trigger), driven through the real controllers:
 * <ul>
 *   <li>each cancel path records why: the sweep {@code EXPIRED}, the buyer's
 *       cancel {@code CUSTOMER_CANCELLED}, a reversal {@code ADMIN_REVERSED};</li>
 *   <li>extend-hold stamps the payment window under the row lock, extends a
 *       lapsed hold the sweep has not cancelled, and refuses — writing
 *       nothing — past the absolute cap;</li>
 *   <li>the customer cancel is refused while a payment may still land;</li>
 *   <li>a payment that lands on a cancelled booking resurrects it for each
 *       resurrectable reason, re-claiming the stock and re-activating the
 *       items through the V5 trigger, and is refused — claiming nothing — for
 *       an admin reversal, a pre-V25 NULL, a deleted category and a sold-out
 *       one;</li>
 *   <li>two confirms of one cancelled booking resurrect it once (the other is
 *       the replay), and a stale confirm cannot bring back a booking resurrected
 *       and then reversed while it waited;</li>
 *   <li>the expiry notice goes out only when a payment had been started.</li>
 * </ul>
 */
class BookingPaymentWindowPostgresIT extends BookingStockPostgresTestBase {

    // Postgres order: LOW < HIGH. Fixed so a two-category booking's claim
    // order is known; each test removes them again (deleteSeeded).
    private static final UUID LOW = UUID.fromString("7fffffff-0000-4000-8000-00000000c25a");
    private static final UUID HIGH = UUID.fromString("80000000-0000-4000-8000-00000000c25b");

    @Autowired private BookingExpirationService expirationService;

    private BookingExpirationService sweep;
    private final UUID category = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        holdTheSweepLock();
        sweep = AopTestUtils.getUltimateTargetObject(expirationService);
        category(UUID.randomUUID(), category, "General", 5);
    }

    @AfterEach
    void tearDown() {
        releaseTheSweepLock();
        deleteSeeded();
    }

    // ---- why a booking was cancelled ---------------------------------------------------

    @Test
    void theSweep_theCustomerCancel_andTheReversal_eachRecordWhy() throws Exception {
        Booking expired = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);
        Booking cancelled = seedBooking(Booking.BookingStatus.PENDING, inMinutes(5), category);
        Booking reversed = seedBooking(Booking.BookingStatus.CONFIRMED, null, category);
        Booking untouched = seedBooking(Booking.BookingStatus.PENDING, inMinutes(5), category);
        AvailabilityResponseDTO released = new AvailabilityResponseDTO();
        released.setAvailableTickets(10);
        when(eventServiceClient.releaseAvailability(any(), anyInt(), anyString()))
                .thenReturn(ApiResult.ok(released));

        sweep.expirePending();
        mvc.perform(patch("/bookings/" + cancelled.getId() + "/cancel").header("Authorization", buyer()))
                .andExpect(status().isOk());
        mvc.perform(patch("/bookings/" + reversed.getId() + "/reverse")
                        .header("Authorization", bearer("admin@innbucks.co.zw", List.of("SUPER_ADMIN"), Map.of())))
                .andExpect(status().isOk());

        assertThat(cancelReasonOf(expired.getId())).isEqualTo("EXPIRED");
        assertThat(cancelReasonOf(cancelled.getId())).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(cancelReasonOf(reversed.getId())).isEqualTo("ADMIN_REVERSED");
        assertThat(cancelReasonOf(untouched.getId())).isNull();
    }

    @Test
    void theCheckRefusesAReasonItDoesNotKnow() {
        Booking booking = seedBooking(Booking.BookingStatus.PENDING, inMinutes(5), category);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE bookings SET status = 'CANCELLED', cancel_reason = 'TIMED_OUT' WHERE id = ?",
                booking.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_cancel_reason");
    }

    // ---- extend-hold -------------------------------------------------------------------------

    @Test
    void extendHold_extendsALapsedHoldTheSweepHasNotCancelled_andStampsThePaymentWindow() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);
        LocalDateTime until = inMinutes(13).truncatedTo(ChronoUnit.SECONDS);

        mvc.perform(extendHold(hold.getId(), until)).andExpect(status().isOk());

        assertThat(statusOf(hold.getId())).isEqualTo("PENDING");
        assertThat(column(hold.getId(), "expires_at")).isEqualTo(until);
        assertThat(column(hold.getId(), "payment_open_until")).isEqualTo(until);
        assertThat(remaining(category)).as("still claimed, never released and re-claimed").isEqualTo(5);
    }

    @Test
    void extendHold_pastSixtyMinutesFromBooking_is409_andWritesNothing() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, inMinutes(2), category);
        jdbc.update("UPDATE bookings SET created_at = ? WHERE id = ?", lapsed(55), hold.getId());
        LocalDateTime before = column(hold.getId(), "expires_at");

        mvc.perform(extendHold(hold.getId(), inMinutes(13).truncatedTo(ChronoUnit.SECONDS)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Your seat reservation has expired. Please start a new "
                        + "booking and complete payment within 5 minutes."));

        assertThat(column(hold.getId(), "expires_at")).as("not clamped").isEqualTo(before);
        assertThat(column(hold.getId(), "payment_open_until")).isNull();
    }

    // ---- the customer cancel waits for an open payment -----------------------------------------

    @Test
    void cancel_whileAPaymentMayLand_is409PaymentInProgress_andAfterTheWindowItCancels() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, inMinutes(5), category);
        mvc.perform(extendHold(hold.getId(), inMinutes(13).truncatedTo(ChronoUnit.SECONDS)))
                .andExpect(status().isOk());

        mvc.perform(patch("/bookings/" + hold.getId() + "/cancel").header("Authorization", buyer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("PAYMENT_IN_PROGRESS"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").isNumber());
        assertThat(statusOf(hold.getId())).isEqualTo("PENDING");
        assertThat(remaining(category)).isEqualTo(5);

        // The last instrument's window has closed: the buyer may cancel.
        jdbc.update("UPDATE bookings SET payment_open_until = ? WHERE id = ?", lapsed(0).minusSeconds(1),
                hold.getId());
        mvc.perform(patch("/bookings/" + hold.getId() + "/cancel").header("Authorization", buyer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(cancelReasonOf(hold.getId())).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(remaining(category)).isEqualTo(6);
    }

    // ---- resurrect -------------------------------------------------------------------------------

    @Test
    void aLatePayment_resurrectsAnExpiredHold() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);
        sweep.expirePending();
        assertCancelled(hold.getId(), "EXPIRED", 6);

        assertResurrected(hold.getId(), "expired", 5);
    }

    @Test
    void aLatePayment_resurrectsABookingTheBuyerCancelled() throws Exception {
        Booking booking = seedBooking(Booking.BookingStatus.PENDING, inMinutes(5), category);
        mvc.perform(patch("/bookings/" + booking.getId() + "/cancel").header("Authorization", buyer()))
                .andExpect(status().isOk());
        assertCancelled(booking.getId(), "CUSTOMER_CANCELLED", 6);

        assertResurrected(booking.getId(), "customer_cancelled", 5);
    }

    @Test
    void aLatePayment_resurrectsABookingTheBuyerReleased() throws Exception {
        Booking booking = seedBooking(Booking.BookingStatus.PENDING, inMinutes(5), category);
        // What P2-4's public release will write; there is no writer yet.
        jdbc.update("UPDATE bookings SET status = 'CANCELLED', cancel_reason = 'BUYER_RELEASED', "
                + "expires_at = NULL WHERE id = ?", booking.getId());
        jdbc.update("UPDATE category_inventory SET remaining = remaining + 1 WHERE category_id = ?", category);
        assertCancelled(booking.getId(), "BUYER_RELEASED", 6);

        assertResurrected(booking.getId(), "buyer_released", 5);
    }

    @Test
    void resurrect_reclaimsEveryCategory_inOneTransaction() throws Exception {
        category(UUID.randomUUID(), LOW, "Bay 1", 3);
        category(UUID.randomUUID(), HIGH, "Bay 2", 3);
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), HIGH, LOW, HIGH);
        sweep.expirePending();
        assertThat(remaining(LOW)).isEqualTo(4);
        assertThat(remaining(HIGH)).isEqualTo(5);

        confirm(hold.getId()).andExpect(status().isOk());

        assertThat(statusOf(hold.getId())).isEqualTo("CONFIRMED");
        assertThat(remaining(LOW)).isEqualTo(3);
        assertThat(remaining(HIGH)).isEqualTo(3);
        assertThat(itemsActive(hold.getId())).containsExactly(true, true, true);
    }

    @Test
    void anAdminReversal_isNeverResurrected() throws Exception {
        Booking booking = seedBooking(Booking.BookingStatus.CONFIRMED, null, category);
        AvailabilityResponseDTO released = new AvailabilityResponseDTO();
        released.setAvailableTickets(10);
        when(eventServiceClient.releaseAvailability(any(), anyInt(), anyString()))
                .thenReturn(ApiResult.ok(released));
        mvc.perform(patch("/bookings/" + booking.getId() + "/reverse")
                        .header("Authorization", bearer("admin@innbucks.co.zw", List.of("SUPER_ADMIN"), Map.of())))
                .andExpect(status().isOk());
        assertCancelled(booking.getId(), "ADMIN_REVERSED", 6);

        assertRefused(booking.getId(), 6);
        verify(seatServiceClient, never()).getCategory(category);
    }

    @Test
    void aBookingCancelledBeforeV25_isNeverResurrected() throws Exception {
        Booking booking = seedBooking(Booking.BookingStatus.PENDING, inMinutes(5), category);
        jdbc.update("UPDATE bookings SET status = 'CANCELLED', expires_at = NULL WHERE id = ?", booking.getId());
        jdbc.update("UPDATE category_inventory SET remaining = remaining + 1 WHERE category_id = ?", category);
        assertCancelled(booking.getId(), null, 6);

        assertRefused(booking.getId(), 6);
        verify(seatServiceClient, never()).getCategory(category);
    }

    @Test
    void aDeletedCategory_isRefused_andCounted() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);
        sweep.expirePending();
        when(seatServiceClient.getCategory(category)).thenReturn(ApiResult.ok((CategoryLookupDTO) null));
        double failedBefore = resurrected("expired", "failed");

        assertRefused(hold.getId(), 6);
        assertThat(resurrected("expired", "failed") - failedBefore).isEqualTo(1);
    }

    @Test
    void seatServiceUnreachable_is503_retriedByPaymentService_andCounted() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);
        sweep.expirePending();
        when(seatServiceClient.getCategory(category))
                .thenThrow(new SeatServiceUnavailableException("seat-service is currently unavailable."));
        double failedBefore = resurrected("expired", "failed");

        confirm(hold.getId()).andExpect(status().isServiceUnavailable());

        assertCancelled(hold.getId(), "EXPIRED", 6);
        assertThat(resurrected("expired", "failed") - failedBefore).isEqualTo(1);
    }

    @Test
    void aSoldOutCategory_isRefused_andTheOtherCategorysClaimRollsBack() throws Exception {
        category(UUID.randomUUID(), LOW, "Bay 1", 3);
        category(UUID.randomUUID(), HIGH, "Bay 2", 3);
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), LOW, HIGH);
        sweep.expirePending();
        // HIGH sold out to someone else since; LOW (claimed first) still has stock.
        jdbc.update("UPDATE category_inventory SET remaining = 0 WHERE category_id = ?", HIGH);
        double failedBefore = resurrected("expired", "failed");

        confirm(hold.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(BookingService.NOT_CONFIRMABLE_MESSAGE));

        assertThat(statusOf(hold.getId())).isEqualTo("CANCELLED");
        assertThat(remaining(LOW)).as("LOW's claim rolled back with the refusal").isEqualTo(4);
        assertThat(remaining(HIGH)).isZero();
        assertThat(itemsActive(hold.getId())).containsOnly(false);
        assertThat(resurrected("expired", "failed") - failedBefore).isEqualTo(1);

        // Stock comes back: payment-service's next retry succeeds.
        jdbc.update("UPDATE category_inventory SET remaining = 1 WHERE category_id = ?", HIGH);
        confirm(hold.getId()).andExpect(status().isOk());
        assertThat(statusOf(hold.getId())).isEqualTo("CONFIRMED");
        assertThat(remaining(LOW)).isEqualTo(3);
        assertThat(remaining(HIGH)).isZero();
    }

    // ---- two confirms of one cancelled booking ---------------------------------------------------

    @Test
    void twoConfirmsAtOnce_resurrectOnce_andTheOtherIsTheReplay() throws Exception {
        // The first confirm after a late payment racing payment-service's
        // one-minute retry. The barrier in seat-service's lookup (phase 2) lets
        // neither go on until both are there, so both have read the booking
        // CANCELLED in phase 1 and both reach phase 3, where they serialise on
        // the row lock. Only the re-check there keeps the second from claiming
        // the stock and publishing BookingConfirmed again.
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);
        sweep.expirePending();
        assertCancelled(hold.getId(), "EXPIRED", 6);
        CyclicBarrier bothInPhaseTwo = new CyclicBarrier(2);
        when(seatServiceClient.getCategory(category)).thenAnswer(inv -> {
            bothInPhaseTwo.await(10, TimeUnit.SECONDS);
            return ApiResult.ok(lookup(category));
        });
        double succeededBefore = resurrected("expired", "succeeded");
        double failedBefore = resurrected("expired", "failed");

        ExecutorService confirms = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = confirms.submit(() -> confirm(hold.getId()).andReturn());
            Future<MvcResult> second = confirms.submit(() -> confirm(hold.getId()).andReturn());
            for (MvcResult result : List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))) {
                assertThat(result.getResponse().getStatus())
                        .as(result.getResponse().getContentAsString()).isEqualTo(200);
                assertThat(result.getResponse().getContentAsString()).contains("\"status\":\"CONFIRMED\"");
            }
        } finally {
            confirms.shutdownNow();
        }

        assertThat(statusOf(hold.getId())).isEqualTo("CONFIRMED");
        assertThat(remaining(category)).as("claimed once, not twice").isEqualTo(5);
        assertThat(resurrected("expired", "succeeded") - succeededBefore).isEqualTo(1);
        assertThat(resurrected("expired", "failed") - failedBefore).isZero();
        // BookingConfirmed published once: event-service's availability is
        // decremented once (and the tickets sent once).
        verify(eventServiceClient, timeout(5_000)).consumeAvailability(eq(hold.getEventId()), eq(1), anyString());
        verify(eventServiceClient, after(500).times(1))
                .consumeAvailability(eq(hold.getEventId()), anyInt(), anyString());
    }

    @Test
    void aStaleConfirm_neverBringsBackABookingResurrectedAndThenReversedMeanwhile() throws Exception {
        // One confirm stops in phase 2 having read CANCELLED(EXPIRED). Meanwhile
        // another confirm resurrects the booking and an admin reverses it (the
        // refund). When the stale one reaches phase 3 the booking is CANCELLED
        // again — by a reversal — and must stay that way with nothing claimed.
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);
        sweep.expirePending();
        CountDownLatch staleInPhaseTwo = new CountDownLatch(1);
        CountDownLatch staleMayGoOn = new CountDownLatch(1);
        AtomicInteger lookups = new AtomicInteger();
        when(seatServiceClient.getCategory(category)).thenAnswer(inv -> {
            if (lookups.getAndIncrement() == 0) {
                staleInPhaseTwo.countDown();
                assertThat(staleMayGoOn.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return ApiResult.ok(lookup(category));
        });
        AvailabilityResponseDTO released = new AvailabilityResponseDTO();
        released.setAvailableTickets(10);
        when(eventServiceClient.releaseAvailability(any(), anyInt(), anyString()))
                .thenReturn(ApiResult.ok(released));
        double failedBefore = resurrected("expired", "failed");

        ExecutorService staleCaller = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> stale = staleCaller.submit(() -> confirm(hold.getId()).andReturn());
            assertThat(staleInPhaseTwo.await(10, TimeUnit.SECONDS)).isTrue();

            confirm(hold.getId()).andExpect(status().isOk());
            assertThat(remaining(category)).isEqualTo(5);
            mvc.perform(patch("/bookings/" + hold.getId() + "/reverse")
                            .header("Authorization", bearer("admin@innbucks.co.zw", List.of("SUPER_ADMIN"), Map.of())))
                    .andExpect(status().isOk());
            assertCancelled(hold.getId(), "ADMIN_REVERSED", 6);

            staleMayGoOn.countDown();
            MvcResult result = stale.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString()).contains(BookingService.NOT_CONFIRMABLE_MESSAGE);
        } finally {
            staleMayGoOn.countDown();
            staleCaller.shutdownNow();
        }

        assertCancelled(hold.getId(), "ADMIN_REVERSED", 6);
        assertThat(itemsActive(hold.getId())).containsOnly(false);
        assertThat(resurrected("expired", "failed") - failedBefore).isEqualTo(1);
    }

    // ---- the expiry notice (D16) -------------------------------------------------------------

    @Test
    void anExpiredHoldIsAnnounced_onlyWhenAPaymentWasStartedOnIt() throws Exception {
        Booking neverPaid = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);
        Booking paymentStarted = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);
        jdbc.update("UPDATE bookings SET phone_number = ? WHERE id = ?", "+263771110001", neverPaid.getId());
        jdbc.update("UPDATE bookings SET phone_number = ?, payment_open_until = ? WHERE id = ?",
                "+263771110002", lapsed(2), paymentStarted.getId());

        sweep.expirePending();

        verify(sms, timeout(5_000)).sendSms(eq("+263771110002"), anyString(), anyString());
        verify(sms, after(500).never()).sendSms(eq("+263771110001"), anyString(), anyString());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void assertResurrected(UUID bookingId, String reasonTag, int remainingAfter) throws Exception {
        double before = resurrected(reasonTag, "succeeded");
        assertThat(itemsActive(bookingId)).as("deactivated by the V5 trigger on the cancel").containsOnly(false);

        confirm(bookingId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        assertThat(statusOf(bookingId)).isEqualTo("CONFIRMED");
        assertThat(cancelReasonOf(bookingId)).as("cleared: the row reads as if paid in time").isNull();
        assertThat(remaining(category)).as("re-claimed").isEqualTo(remainingAfter);
        assertThat(itemsActive(bookingId)).as("re-activated by the V5 trigger").containsOnly(true);
        assertThat(resurrected(reasonTag, "succeeded") - before).isEqualTo(1);

        // A retry of the same payment is the ordinary replay: nothing claimed twice.
        confirm(bookingId).andExpect(status().isOk());
        assertThat(remaining(category)).isEqualTo(remainingAfter);
        assertThat(resurrected(reasonTag, "succeeded") - before).isEqualTo(1);
    }

    private void assertRefused(UUID bookingId, int remainingStays) throws Exception {
        confirm(bookingId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(BookingService.NOT_CONFIRMABLE_MESSAGE));
        assertThat(statusOf(bookingId)).isEqualTo("CANCELLED");
        assertThat(remaining(category)).as("nothing claimed").isEqualTo(remainingStays);
        assertThat(itemsActive(bookingId)).containsOnly(false);
    }

    private void assertCancelled(UUID bookingId, String reason, int remainingNow) {
        assertThat(statusOf(bookingId)).isEqualTo("CANCELLED");
        assertThat(cancelReasonOf(bookingId)).isEqualTo(reason);
        assertThat(remaining(category)).isEqualTo(remainingNow);
    }

    private org.springframework.test.web.servlet.ResultActions confirm(UUID bookingId) throws Exception {
        return mvc.perform(patch("/bookings/internal/" + bookingId + "/confirm")
                .header("X-Internal-Token", internalToken));
    }

    private org.springframework.test.web.servlet.RequestBuilder extendHold(UUID bookingId, LocalDateTime until) {
        return patch("/bookings/internal/" + bookingId + "/extend-hold")
                .header("X-Internal-Token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"holdUntil\":\"" + until + "Z\"}");
    }

    private String buyer() {
        return bearer("buyer@example.com", List.of("CUSTOMER"), Map.of());
    }

    private String cancelReasonOf(UUID bookingId) {
        return jdbc.queryForObject("SELECT cancel_reason FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private LocalDateTime column(UUID bookingId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM bookings WHERE id = ?", LocalDateTime.class,
                bookingId);
    }

    private List<Boolean> itemsActive(UUID bookingId) {
        return jdbc.queryForList("SELECT is_active FROM booking_items WHERE booking_id = ? ORDER BY seat_number",
                Boolean.class, bookingId);
    }

    private static CategoryLookupDTO lookup(UUID categoryId) {
        return CategoryLookupDTO.builder().seatCategoryId(categoryId).name("General")
                .price(new BigDecimal("10.00")).totalSeats(5).build();
    }

    private double resurrected(String reason, String outcome) {
        return meterRegistry.get(BookingMetrics.CONFIRM_RESURRECTED)
                .tag("reason", reason).tag("outcome", outcome).counter().count();
    }

    private static LocalDateTime inMinutes(int minutes) {
        return LocalDateTime.now(ZoneOffset.UTC).plusMinutes(minutes);
    }
}
