package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.service.StockLedger;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_BAD_INTERNAL_TOKEN;
import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_FORBIDDEN;
import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_HOLD_CAP_REACHED;
import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_HOLD_NOT_EXTENDABLE;
import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_INVALID_JWT;
import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_NOT_AN_OBJECT;
import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_NOT_FOUND;
import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_UNREADABLE_HOLD_UNTIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Swagger error examples of cancel, confirm, reverse and extend-hold,
 * compared STRICTLY against what the running service answers: the real
 * filter chain, the app's own JSON mapper (with its {@code LocalDateTime}
 * module, which the standalone dispatch test does not have) and real
 * Postgres. A Swagger example is code nothing executes; this executes it.
 *
 * <p>The extend-hold race is the one that matters most. payment-service
 * reads its 409 as "the booking expired, refuse the payment", where a 500
 * reads as an outage. Since H2 extend-hold takes the booking row's lock before
 * it reads, so against a sweep holding that row it waits, then reads what the
 * sweep committed and refuses it as not PENDING. (Under H1 it read without a
 * lock and lost at its version-checked UPDATE: {@code CONCURRENT_UPDATE}.)
 * Without the lock that race is a 200 that writes the booking back to PENDING
 * over the cancellation, or — without the sweep's version bump — a 500.
 */
class BookingErrorExamplesPostgresIT extends BookingStockPostgresTestBase {

    @Autowired private CategoryInventoryRepository categoryInventoryRepository;

    private final ExecutorService caller = Executors.newSingleThreadExecutor();
    private final ExecutorService sweeper = Executors.newSingleThreadExecutor();
    private final UUID category = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        holdTheSweepLock();
        category(UUID.randomUUID(), category, "General", 5);
    }

    @AfterEach
    void tearDown() {
        caller.shutdownNow();
        sweeper.shutdownNow();
        releaseTheSweepLock();
        deleteSeeded();
    }

    // ---- extend-hold -------------------------------------------------------------

    @Test
    void extendHold_thatTheExpirySweepCancelsUnderIt_waitsForTheRow_andIsTheDocumented409() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1), category);
        CountDownLatch sweepHasTheRow = new CountDownLatch(1);
        CountDownLatch sweepMayCommit = new CountDownLatch(1);

        // The sweep's own statements for one booking, paused while it holds
        // the row: lock (SKIP LOCKED), release the tickets, mark it expired.
        // The hold has not lapsed yet, so this stands in for a sweep whose
        // clock is a moment ahead of extend-hold's; the statements are real.
        Future<?> sweep = sweeper.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Booking locked = bookingRepository.lockByIdSkipLocked(hold.getId()).orElseThrow();
            new StockLedger(categoryInventoryRepository).release(locked);
            sweepHasTheRow.countDown();
            awaitQuietly(sweepMayCommit);
            assertThat(bookingRepository.markExpired(hold.getId(), LocalDateTime.now(ZoneOffset.UTC)))
                    .isEqualTo(1);
        }));
        assertThat(sweepHasTheRow.await(10, TimeUnit.SECONDS)).isTrue();

        // extend-hold locks the row before it reads it, so it waits for the
        // sweep rather than reading the PENDING row the sweep is cancelling.
        String holdUntil = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)
                .truncatedTo(ChronoUnit.SECONDS) + "Z";
        Future<MvcResult> extend = caller.submit(() -> mvc.perform(
                patch("/bookings/internal/" + hold.getId() + "/extend-hold")
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdUntil\":\"" + holdUntil + "\"}")).andReturn());
        awaitALockWaiter();
        assertThat(extend.isDone()).as("extend-hold must be waiting for the row lock").isFalse();

        sweepMayCommit.countDown();
        sweep.get(10, TimeUnit.SECONDS);

        MvcResult result = extend.get(10, TimeUnit.SECONDS);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).as("the documented body")
                .isEqualToIgnoringWhitespace(EXAMPLE_HOLD_NOT_EXTENDABLE);

        // The sweep's cancellation stands; extend-hold wrote nothing over it.
        assertThat(statusOf(hold.getId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT expires_at FROM bookings WHERE id = ?", LocalDateTime.class,
                hold.getId())).isNull();
        assertThat(remaining(category)).as("released once, by the sweep").isEqualTo(6);
    }

    @Test
    void extendHold_pastTheHoldCap_isTheDocumented409_andWritesNothing() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(2), category);
        jdbc.update("UPDATE bookings SET created_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(50), hold.getId());

        // 50 minutes old + a 13-minute code = past the 60-minute cap.
        mvc.perform(patch("/bookings/internal/" + hold.getId() + "/extend-hold")
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdUntil\":\"" + LocalDateTime.now(ZoneOffset.UTC).plusMinutes(13)
                                .truncatedTo(ChronoUnit.SECONDS) + "Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json(EXAMPLE_HOLD_CAP_REACHED, JsonCompareMode.STRICT));

        assertThat(jdbc.queryForObject("SELECT payment_open_until FROM bookings WHERE id = ?",
                LocalDateTime.class, hold.getId())).isNull();
    }

    @Test
    void extendHold_anUnreadableHoldUntil_isTheDocumented400() throws Exception {
        // "tomorrow" is a string that is no date-time; 12, {} and true are not
        // strings at all. All are the same answer, keyed by the field.
        for (String value : List.of("\"tomorrow\"", "\"2026-13-45T25:00:00\"", "12", "{}", "true")) {
            mvc.perform(patch("/bookings/internal/" + UUID.randomUUID() + "/extend-hold")
                            .header("X-Internal-Token", internalToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"holdUntil\":" + value + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json(EXAMPLE_UNREADABLE_HOLD_UNTIL, JsonCompareMode.STRICT));
        }
    }

    @Test
    void aBodyThatIsJsonButNotOneObject_isTheDocumented400_notInvalidJson() throws Exception {
        for (String body : List.of("[]", "\"2026-10-13T10:00:00Z\"",
                "{\"holdUntil\":\"2030-01-01T00:00:00Z\"} {\"holdUntil\":\"2030-01-01T00:00:00Z\"}")) {
            mvc.perform(patch("/bookings/internal/" + UUID.randomUUID() + "/extend-hold")
                            .header("X-Internal-Token", internalToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json(EXAMPLE_NOT_AN_OBJECT, JsonCompareMode.STRICT));
        }
        // Broken JSON is still called broken.
        mvc.perform(patch("/bookings/internal/" + UUID.randomUUID() + "/extend-hold")
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdUntil\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.body").value("The request body is not valid JSON."));
    }

    @Test
    void extendHold_aBadInternalToken_andAnUnknownBooking_areTheDocumentedBodies() throws Exception {
        String body = "{\"holdUntil\":\"" + LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)
                .truncatedTo(ChronoUnit.SECONDS) + "Z\"}";
        mvc.perform(patch("/bookings/internal/" + UUID.randomUUID() + "/extend-hold")
                        .header("X-Internal-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(EXAMPLE_BAD_INTERNAL_TOKEN, JsonCompareMode.STRICT));
        mvc.perform(patch("/bookings/internal/" + UUID.randomUUID() + "/extend-hold")
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(content().json(EXAMPLE_NOT_FOUND, JsonCompareMode.STRICT));
    }

    // ---- cancel ------------------------------------------------------------------

    @Test
    void cancel_bySomeoneOtherThanTheOwner_isTheDocumented403_andChangesNothing() throws Exception {
        Booking hold = seedBooking(Booking.BookingStatus.PENDING,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10), category);

        mvc.perform(patch("/bookings/" + hold.getId() + "/cancel")
                        .header("Authorization", bearer("someone-else@example.com", List.of("CUSTOMER"), Map.of())))
                .andExpect(status().isForbidden())
                .andExpect(content().json(EXAMPLE_FORBIDDEN, JsonCompareMode.STRICT));

        assertThat(statusOf(hold.getId())).isEqualTo("PENDING");
        assertThat(remaining(category)).isEqualTo(5);
    }

    @Test
    void cancel_withNoToken_andOfAnUnknownBooking_areTheDocumentedBodies() throws Exception {
        mvc.perform(patch("/bookings/" + UUID.randomUUID() + "/cancel"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(EXAMPLE_INVALID_JWT, JsonCompareMode.STRICT));
        mvc.perform(patch("/bookings/" + UUID.randomUUID() + "/cancel")
                        .header("Authorization", bearer("buyer@example.com", List.of("CUSTOMER"), Map.of())))
                .andExpect(status().isNotFound())
                .andExpect(content().json(EXAMPLE_NOT_FOUND, JsonCompareMode.STRICT));
    }

    // ---- confirm and reverse --------------------------------------------------------

    @Test
    void confirm_ofAnUnknownBooking_andWithABadToken_areTheDocumentedBodies() throws Exception {
        mvc.perform(patch("/bookings/internal/" + UUID.randomUUID() + "/confirm")
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isNotFound())
                .andExpect(content().json(EXAMPLE_NOT_FOUND, JsonCompareMode.STRICT));
        mvc.perform(patch("/bookings/internal/" + UUID.randomUUID() + "/confirm")
                        .header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(EXAMPLE_BAD_INTERNAL_TOKEN, JsonCompareMode.STRICT));
        mvc.perform(patch("/bookings/internal/" + UUID.randomUUID() + "/confirm")
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cashAmount\":\"ten\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.cashAmount").value("Must be a number."));
    }

    @Test
    void reverse_ofAnUnknownBooking_andByANonAdmin_areTheDocumentedBodies() throws Exception {
        mvc.perform(patch("/bookings/" + UUID.randomUUID() + "/reverse")
                        .header("Authorization", bearer("admin@innbucks.co.zw", List.of("SUPER_ADMIN"), Map.of())))
                .andExpect(status().isNotFound())
                .andExpect(content().json(EXAMPLE_NOT_FOUND, JsonCompareMode.STRICT));
        mvc.perform(patch("/bookings/" + UUID.randomUUID() + "/reverse")
                        .header("Authorization", bearer("buyer@example.com", List.of("CUSTOMER"), Map.of())))
                .andExpect(status().isForbidden())
                .andExpect(content().json(EXAMPLE_FORBIDDEN, JsonCompareMode.STRICT));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("the test never let the sweep commit");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
