package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.SeatResponseDTO;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where each seat-lock store (Redis) call runs relative to the database
 * transaction, on real Postgres.
 *
 * <p>Only one call is allowed outside: confirmSeat's delete of the owner key,
 * which runs once BOOKED has committed and the connection is back in the pool,
 * and never when the booking fails to commit. The others stay inside on
 * purpose, and these cases pin why: lockSeat's put is undone with the hold;
 * releaseSeat's and the reaper's deletes run under the seat's row lock, so the
 * next holder's put cannot land before them.
 *
 * <p>The store is a Mockito bean whose answers RECORD the calling thread's
 * state (transaction active? resource bound?) and, where it matters, what
 * another connection sees of the seat row at that moment.
 */
class SeatLockStoreTransactionOrderingPostgresIT extends PostgresIntegrationTestBase {

    private static final String BUYER = "buyer@example.com";

    @MockitoBean private SeatLockStore seatLockStore;

    @Autowired private SeatService seatService;
    @Autowired private SeatCategoryRepository categoryRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private TransactionTemplate tx;
    /** One line per store call: "what|txActive|resourcesBound|seatSeenFromAnotherConnection". */
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

    // ---- confirmSeat: the one call that moved -------------------------------

    @Test
    void confirm_deletesTheOwnerOnlyAfterBookedHasCommitted_withNoConnectionHeld() {
        UUID seatId = seedSeat(Seat.SeatStatus.LOCKED, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        String key = SeatService.LOCK_KEY_PREFIX + seatId;
        when(seatLockStore.get(key)).thenAnswer(inv -> {
            record("get", seatId);
            return BUYER;
        });
        doAnswer(inv -> {
            record("delete", seatId);
            return null;
        }).when(seatLockStore).delete(key);

        SeatResponseDTO result = seatService.confirmSeat(seatId, BUYER);

        assertThat(result.getStatus()).isEqualTo(Seat.SeatStatus.BOOKED);
        // The ownership read is the gate for the sale: inside the transaction
        // (confirm reads the row without locking it; @Version guards the
        // write). The delete: no transaction, no connection, and the row is
        // BOOKED, committed and unlocked as seen from another connection.
        assertThat(calls).containsExactly(
                "get|true|true|LOCKED",
                "delete|false|false|BOOKED");
    }

    @Test
    void confirm_whoseCommitFails_neverDeletesTheOwnerKey() {
        // The hold went stale and another customer reclaimed it while this
        // confirm was in flight: their lockSeat bumped the row's @Version and
        // PUT their own owner under the same key. This confirm's commit fails on
        // the version — and must not take the new holder's key with it, or they
        // could never confirm a seat the database says is theirs.
        UUID seatId = seedSeat(Seat.SeatStatus.LOCKED, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        String key = SeatService.LOCK_KEY_PREFIX + seatId;
        when(seatLockStore.get(key)).thenAnswer(inv -> {
            reclaimFromAnotherConnection(seatId);
            return BUYER;
        });

        assertThatThrownBy(() -> seatService.confirmSeat(seatId, BUYER))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(seatLockStore, never()).delete(anyString());
        assertThat(statusOf(seatId)).isEqualTo("LOCKED");
    }

    @Test
    void confirm_aDeleteThatFailsAfterCommit_stillReportsTheBooking() {
        UUID seatId = seedSeat(Seat.SeatStatus.LOCKED, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        String key = SeatService.LOCK_KEY_PREFIX + seatId;
        when(seatLockStore.get(key)).thenReturn(BUYER);
        doThrow(new IllegalStateException("redis timed out")).when(seatLockStore).delete(key);

        SeatResponseDTO result = seatService.confirmSeat(seatId, BUYER);

        assertThat(result.getStatus()).isEqualTo(Seat.SeatStatus.BOOKED);
        assertThat(statusOf(seatId)).isEqualTo("BOOKED");
    }

    @Test
    void confirm_aStoreThatCannotAnswerTheOwner_failsClosed() {
        UUID seatId = seedSeat(Seat.SeatStatus.LOCKED, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        when(seatLockStore.get(SeatService.LOCK_KEY_PREFIX + seatId))
                .thenThrow(new IllegalStateException("redis timed out"));

        assertThatThrownBy(() -> seatService.confirmSeat(seatId, BUYER))
                .isInstanceOf(IllegalStateException.class);

        assertThat(statusOf(seatId)).isEqualTo("LOCKED");
        verify(seatLockStore, never()).delete(anyString());
    }

    // ---- the calls that stay inside, and why -------------------------------

    @Test
    void lock_putsTheOwnerInsideTheTransaction_whileTheSeatRowIsLocked() {
        UUID seatId = seedSeat(Seat.SeatStatus.AVAILABLE, null);
        doAnswer(inv -> {
            record("put", seatId);
            return null;
        }).when(seatLockStore).put(eq(SeatService.LOCK_KEY_PREFIX + seatId), eq(BUYER), anyLong());

        seatService.lockSeat(seatId, BUYER);

        assertThat(calls).containsExactly("put|true|true|LOCKED_BY_OTHER");
        assertThat(statusOf(seatId)).isEqualTo("LOCKED");
    }

    @Test
    void lock_aPutThatFails_undoesTheWholeHold() {
        UUID seatId = seedSeat(Seat.SeatStatus.AVAILABLE, null);
        doThrow(new IllegalStateException("redis timed out"))
                .when(seatLockStore).put(anyString(), anyString(), anyLong());

        assertThatThrownBy(() -> seatService.lockSeat(seatId, BUYER))
                .isInstanceOf(IllegalStateException.class);

        // No hold without an owner: the seat and the category counter are back.
        assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject(
                "SELECT c.available_seats FROM seat_categories c JOIN seats s ON s.category_id = c.id WHERE s.id = ?",
                Integer.class, seatId)).isEqualTo(2);
    }

    @Test
    void release_deletesTheOwnerInsideTheTransaction_underTheSeatRowLock() {
        UUID seatId = seedSeat(Seat.SeatStatus.LOCKED, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        String key = SeatService.LOCK_KEY_PREFIX + seatId;
        when(seatLockStore.get(key)).thenAnswer(inv -> {
            record("get", seatId);
            return BUYER;
        });
        doAnswer(inv -> {
            record("delete", seatId);
            return null;
        }).when(seatLockStore).delete(key);

        seatService.releaseSeat(seatId, BUYER);

        // Still row-locked during the delete: the next lockSeat blocks on the
        // row until this commits, so its PUT cannot land before our DELETE.
        assertThat(calls).containsExactly(
                "get|true|true|LOCKED_BY_OTHER",
                "delete|true|true|LOCKED_BY_OTHER");
        assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
    }

    @Test
    void reaper_deletesTheOwnerInsideTheTransaction_underTheSeatRowLock() {
        UUID seatId = seedSeat(Seat.SeatStatus.LOCKED, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        doAnswer(inv -> {
            record("delete", seatId);
            return null;
        }).when(seatLockStore).delete(SeatService.LOCK_KEY_PREFIX + seatId);

        assertThat(seatService.releaseStaleLock(seatId)).isTrue();

        assertThat(calls).containsExactly("delete|true|true|LOCKED_BY_OTHER");
        assertThat(statusOf(seatId)).isEqualTo("AVAILABLE");
    }

    // ---- helpers -------------------------------------------------------------

    private void record(String what, UUID seatId) {
        calls.add(what
                + "|" + TransactionSynchronizationManager.isActualTransactionActive()
                + "|" + !TransactionSynchronizationManager.getResourceMap().isEmpty()
                + "|" + lockAndReadStatus(seatId));
    }

    /**
     * From a different connection (REQUIRES_NEW, whatever the caller holds):
     * lock the seat row with NOWAIT and read its committed status;
     * "LOCKED_BY_OTHER" when the row lock is refused.
     */
    private String lockAndReadStatus(UUID seatId) {
        TransactionTemplate other = new TransactionTemplate(txManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return other.execute(st -> jdbc.queryForObject(
                    "SELECT status FROM seats WHERE id = ? FOR UPDATE NOWAIT", String.class, seatId));
        } catch (DataAccessException lockNotAvailable) {
            return "LOCKED_BY_OTHER";
        }
    }

    /** Another customer's stale-hold reclaim, committed from another connection. */
    private void reclaimFromAnotherConnection(UUID seatId) {
        TransactionTemplate other = new TransactionTemplate(txManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        other.executeWithoutResult(st -> jdbc.update(
                "UPDATE seats SET version = version + 1, lock_expires_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)), seatId));
    }

    private String statusOf(UUID seatId) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seatId);
    }

    private UUID seedSeat(Seat.SeatStatus status, LocalDateTime lockExpiresAt) {
        return tx.execute(s -> {
            SeatCategory category = categoryRepository.save(SeatCategory.builder()
                    .eventId(UUID.randomUUID())
                    .name("General")
                    .price(new BigDecimal("10.00"))
                    .totalSeats(2)
                    .availableSeats(status == Seat.SeatStatus.AVAILABLE ? 2 : 1)
                    .deleted(false)
                    .build());
            return seatRepository.save(Seat.builder()
                    .category(category)
                    .sectionLabel("A")
                    .seatNumber(1)
                    .status(status)
                    .lockExpiresAt(lockExpiresAt)
                    .build()).getId();
        });
    }
}
