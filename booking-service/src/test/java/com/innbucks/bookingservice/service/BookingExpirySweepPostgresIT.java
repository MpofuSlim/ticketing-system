package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-booking expiry sweep against real Postgres: a hold whose
 * transaction FAILS is rolled back on its own, counted, and stepped over by
 * the keyset cursor, so it can never stop the holds behind it — the failure
 * mode of the old single-transaction sweep, where one bad row rolled the whole
 * batch back and came first again on every run.
 *
 * <p>The failure is a real one from Postgres: a trigger refusing the UPDATE of
 * one counter row, i.e. that hold's stock release.
 */
class BookingExpirySweepPostgresIT extends BookingStockPostgresTestBase {

    @Autowired private BookingExpirationService expirationService;

    private BookingExpirationService sweep;
    private final String trigger = "it_fail_release_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void setUp() {
        holdTheSweepLock();
        // The target, not the ShedLock proxy: the lock row is held above.
        sweep = AopTestUtils.getUltimateTargetObject(expirationService);
    }

    @AfterEach
    void tearDown() {
        sweep.pageSize = BookingExpirationService.PAGE_SIZE;
        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON category_inventory");
        jdbc.execute("DROP FUNCTION IF EXISTS " + trigger + "()");
        releaseTheSweepLock();
        deleteSeeded();
    }

    @Test
    void aFailingHold_isSkippedAndCounted_theCursorReachesEveryHoldBehindIt_acrossPagesAndTies() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        category(UUID.randomUUID(), failing, "Bay 1", 10);
        category(UUID.randomUUID(), ok, "Bay 2", 10);
        failReleasesOf(failing);

        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30).truncatedTo(ChronoUnit.SECONDS);
        // Oldest first: the failing hold is the head of the queue. Two holds
        // share an expiry (a tie the cursor must break on id), and a page is
        // two candidates, so the run needs three pages.
        Booking head = seedBooking(Booking.BookingStatus.PENDING, base, failing);
        Booking tieA = seedBooking(Booking.BookingStatus.PENDING, base.plusMinutes(1), ok);
        Booking tieB = seedBooking(Booking.BookingStatus.PENDING, base.plusMinutes(1), ok);
        Booking later = seedBooking(Booking.BookingStatus.PENDING, base.plusMinutes(2), ok);
        Booking last = seedBooking(Booking.BookingStatus.PENDING, base.plusMinutes(3), ok);
        Booking notDue = seedBooking(Booking.BookingStatus.PENDING,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10), ok);
        sweep.pageSize = 2;
        double skippedBefore = expirySkipped();

        sweep.expirePending();

        assertThat(statusOf(head.getId())).as("rolled back, still PENDING").isEqualTo("PENDING");
        assertThat(remaining(failing)).as("its release rolled back with it").isEqualTo(10);
        for (Booking b : List.of(tieA, tieB, later, last)) {
            assertThat(statusOf(b.getId())).as("behind the failing head").isEqualTo("CANCELLED");
        }
        assertThat(remaining(ok)).isEqualTo(14);
        assertThat(statusOf(notDue.getId())).isEqualTo("PENDING");
        assertThat(expirySkipped() - skippedBefore).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM bookings WHERE id = ?", LocalDateTime.class,
                tieA.getId())).as("the native UPDATE stamps updated_at itself, in UTC")
                .isAfter(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        assertThat(jdbc.queryForObject("SELECT expires_at FROM bookings WHERE id = ?", LocalDateTime.class,
                tieA.getId())).isNull();

        // Every run retries it, and counts it again while it keeps failing.
        sweep.expirePending();
        assertThat(statusOf(head.getId())).isEqualTo("PENDING");
        assertThat(expirySkipped() - skippedBefore).isEqualTo(2);

        // Fixed: the next run cancels it.
        jdbc.execute("DROP TRIGGER " + trigger + " ON category_inventory");
        sweep.expirePending();
        assertThat(statusOf(head.getId())).isEqualTo("CANCELLED");
        assertThat(remaining(failing)).isEqualTo(11);
        assertThat(expirySkipped() - skippedBefore).isEqualTo(2);
    }

    @Test
    void theStatusChangeBumpsTheVersion_soAWriterHoldingAnOlderReadLoses() {
        UUID ok = UUID.randomUUID();
        category(UUID.randomUUID(), ok, "Bay 2", 10);
        Booking hold = seedBooking(Booking.BookingStatus.PENDING, lapsed(1), ok);
        Long before = jdbc.queryForObject("SELECT version FROM bookings WHERE id = ?", Long.class, hold.getId());

        sweep.expirePending();

        assertThat(statusOf(hold.getId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT version FROM bookings WHERE id = ?", Long.class, hold.getId()))
                .isEqualTo(before + 1);
    }

    private void failReleasesOf(UUID categoryId) {
        jdbc.execute("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'release refused by the test'; END $$");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON category_inventory FOR EACH ROW "
                + "WHEN (OLD.category_id = '" + categoryId + "') EXECUTE FUNCTION " + trigger + "()");
    }
}
