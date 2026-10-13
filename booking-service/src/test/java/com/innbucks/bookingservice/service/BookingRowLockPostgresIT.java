package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * The booking row lock against real Postgres, with the competing lock held
 * from ANOTHER connection:
 * <ul>
 *   <li>confirm WAITS for a row someone else holds, then acts on what they
 *       committed: a hold the sweep cancelled is refused, a lapsed hold still
 *       PENDING is honoured;</li>
 *   <li>cancel and reverse take the booking row BEFORE any counter
 *       row — while they wait for the booking, the counter row is
 *       provably free and unchanged. That is the first link of the global lock
 *       order, and the reason none of them can deadlock with a claim.</li>
 * </ul>
 */
class BookingRowLockPostgresIT extends BookingStockPostgresTestBase {

    private final ExecutorService caller = Executors.newSingleThreadExecutor();
    private final UUID category = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        holdTheSweepLock();
        category(UUID.randomUUID(), category, "General", 5);
    }

    @AfterEach
    void tearDown() {
        caller.shutdownNow();
        releaseTheSweepLock();
        deleteSeeded();
    }

    // ---- confirm --------------------------------------------------------------

    @Test
    void confirm_waitsForTheRow_thenRefusesAHoldTheSweepCancelledMeanwhile() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);

        try (Connection sweep = lockBookingRowElsewhere(hold.getId())) {
            Future<MvcResult> confirm = caller.submit(() -> mvc.perform(
                    patch("/bookings/internal/" + hold.getId() + "/confirm")
                            .header("X-Internal-Token", internalToken)).andReturn());
            awaitALockWaiter();
            assertThat(confirm.isDone()).as("confirm must wait for the row, not read past it").isFalse();

            // What the sweep commits for this hold while confirm waits.
            try (PreparedStatement ps = sweep.prepareStatement("""
                    UPDATE bookings SET status = 'CANCELLED', expires_at = NULL, version = version + 1
                     WHERE id = ?""")) {
                ps.setObject(1, hold.getId());
                ps.executeUpdate();
            }
            sweep.commit();

            MvcResult result = confirm.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .contains("Only pending bookings can be confirmed");
        }
        assertThat(statusOf(hold.getId())).isEqualTo("CANCELLED");
    }

    @Test
    void confirm_waitsForTheRow_thenHonoursALapsedHoldThatIsStillPending() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), category);

        try (Connection other = lockBookingRowElsewhere(hold.getId())) {
            Future<MvcResult> confirm = caller.submit(() -> mvc.perform(
                    patch("/bookings/internal/" + hold.getId() + "/confirm")
                            .header("X-Internal-Token", internalToken)).andReturn());
            awaitALockWaiter();
            assertThat(confirm.isDone()).isFalse();
            other.rollback();

            MvcResult result = confirm.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString()).isEqualTo(200);
        }
        assertThat(statusOf(hold.getId())).isEqualTo("CONFIRMED");
        // Its ticket stays sold: confirm never touches the counter.
        assertThat(remaining(category)).isEqualTo(5);
    }

    // ---- every stock path: booking row first ------------------------------------

    @Test
    void cancel_takesTheBookingRowBeforeAnyCounterRow() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10), category, category);
        String customer = bearer("buyer@example.com", List.of("CUSTOMER"), Map.of());

        try (Connection other = lockBookingRowElsewhere(hold.getId())) {
            Future<MvcResult> cancel = caller.submit(() -> mvc.perform(
                    patch("/bookings/" + hold.getId() + "/cancel").header("Authorization", customer)).andReturn());
            awaitALockWaiter();

            assertThat(counterRowIsFree(category)).as("cancel holds no counter row while it waits").isTrue();
            assertThat(remaining(category)).isEqualTo(5);
            other.rollback();

            MvcResult result = cancel.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString()).isEqualTo(200);
        }
        assertThat(statusOf(hold.getId())).isEqualTo("CANCELLED");
        assertThat(remaining(category)).isEqualTo(7);
    }

    @Test
    void reverse_takesTheBookingRowBeforeAnyCounterRow() throws Exception {
        Booking sold = seedBooking(Booking.BookingStatus.CONFIRMED, null, category);
        AvailabilityResponseDTO released = new AvailabilityResponseDTO();
        released.setAvailableTickets(10);
        when(eventServiceClient.releaseAvailability(eq(sold.getEventId()), anyInt(), anyString()))
                .thenReturn(ApiResult.ok(released));
        String admin = bearer("admin@innbucks.co.zw", List.of("SUPER_ADMIN"), Map.of());

        try (Connection other = lockBookingRowElsewhere(sold.getId())) {
            Future<MvcResult> reverse = caller.submit(() -> mvc.perform(
                    patch("/bookings/" + sold.getId() + "/reverse").header("Authorization", admin)).andReturn());
            // Its first phase reads without a lock; it blocks at the write
            // phase's row lock, after the event-service call.
            awaitALockWaiter();

            assertThat(counterRowIsFree(category)).as("reverse holds no counter row while it waits").isTrue();
            assertThat(remaining(category)).isEqualTo(5);
            other.rollback();

            MvcResult result = reverse.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString()).isEqualTo(200);
        }
        assertThat(statusOf(sold.getId())).isEqualTo("CANCELLED");
        assertThat(remaining(category)).isEqualTo(6);
    }
}
