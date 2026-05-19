package innbucks.paymentservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TransferLimitServiceTest {

    private static final BigDecimal PER_TX = new BigDecimal("100000");
    private static final BigDecimal PER_DAY = new BigDecimal("500000");
    private static final String ACCOUNT = "A000001";
    private static final int SCALE = 4;

    /** NUMERIC(19,4) -> long (Redis INCRBY's integer unit). */
    private static long units(String amount) {
        return new BigDecimal(amount).movePointRight(SCALE).longValueExact();
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> ops;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        ops = mock(ValueOperations.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
    }

    private TransferLimitService newService() {
        return new TransferLimitService(PER_TX, PER_DAY, redis);
    }

    // ---------- enforce: per-transaction cap ----------

    @Test
    void enforce_throws_whenAmountExceedsPerTransactionCap() {
        // Single-shot guard runs BEFORE Redis. A typo turning "100" into
        // "100001" lands here without burning a Redis round-trip.
        TransferLimitService svc = newService();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.enforce(ACCOUNT, new BigDecimal("100001")));
        assertTrue(ex.getMessage().contains("Per-transaction limit"));
        verifyNoInteractions(redis);
    }

    // ---------- enforce: atomic INCRBY ----------

    @Test
    void enforce_atomicallyIncrementsRedisCounter_keyedByAccountAndDate() {
        when(ops.increment(any(String.class), anyLong())).thenReturn(units("80000"));
        when(redis.expire(any(String.class), any(Duration.class))).thenReturn(true);

        newService().enforce(ACCOUNT, new BigDecimal("80000"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> deltaCaptor = ArgumentCaptor.forClass(Long.class);
        verify(ops).increment(keyCaptor.capture(), deltaCaptor.capture());

        // Key shape: velocity:daily:{accountId}:{date} — date scopes the
        // counter so it naturally resets each calendar day.
        assertTrue(keyCaptor.getValue().startsWith("velocity:daily:" + ACCOUNT + ":"),
                "key must start with velocity:daily:" + ACCOUNT + ": but was " + keyCaptor.getValue());
        assertTrue(keyCaptor.getValue().endsWith(LocalDate.now().toString()),
                "key must end with today's date but was " + keyCaptor.getValue());
        assertEquals(units("80000"), deltaCaptor.getValue(),
                "INCRBY delta must be the amount converted to NUMERIC(19,4) integer units");

        // TTL set so the counter rolls each midnight without manual cleanup.
        verify(redis).expire(eq(keyCaptor.getValue()), eq(Duration.ofHours(25)));
    }

    @Test
    void enforce_throws_whenIncrementPushesTotalOverDailyCap_andRollsBackTheIncrement() {
        // Initial state: 450k already today. New request adds 60k -> 510k
        // (> 500k cap). The service must (a) reject, (b) decrement Redis
        // back to 450k so the next legitimate request still has the
        // right baseline.
        when(ops.increment(any(String.class), eq(units("60000")))).thenReturn(units("510000"));
        when(redis.expire(any(String.class), any(Duration.class))).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> newService().enforce(ACCOUNT, new BigDecimal("60000")));

        assertTrue(ex.getMessage().contains("Daily limit exceeded"));
        assertTrue(ex.getMessage().contains("510000"), "projected total must surface in the message");
        assertTrue(ex.getMessage().contains("450000"), "today-so-far must surface so customer knows how much they've spent");

        // Compensating DECRBY runs before the throw so the next request
        // sees the correct baseline.
        verify(ops).increment(any(String.class), eq(-units("60000")));
    }

    @Test
    void enforce_passesAtExactBoundary_butThrowsOneCentOver() {
        // Boundary: total == cap is allowed (compareTo > 0 semantics).
        // Pinned so a refactor to ">=" doesn't silently flip it.
        when(ops.increment(any(String.class), eq(units("100000")))).thenReturn(units("500000"));
        when(redis.expire(any(String.class), any(Duration.class))).thenReturn(true);

        assertDoesNotThrow(() -> newService().enforce(ACCOUNT, new BigDecimal("100000")));

        // One cent over the cap (using 4-decimal units): rejected.
        when(ops.increment(any(String.class), eq(units("0.0001"))))
                .thenReturn(units("500000") + 1);
        assertThrows(IllegalArgumentException.class,
                () -> newService().enforce(ACCOUNT, new BigDecimal("0.0001")));
    }

    // ---------- enforce: fail-closed on Redis unreachable ----------

    @Test
    void enforce_failsClosed_whenRedisIsUnreachable() {
        // Banking-grade default: if we can't verify the cap, reject. Better
        // to deny a legitimate transfer than allow an attacker to bypass
        // velocity when Redis is down.
        when(ops.increment(any(String.class), anyLong()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> newService().enforce(ACCOUNT, new BigDecimal("100")));
        assertTrue(ex.getMessage().contains("temporarily unavailable"),
                "message must signal a retryable infrastructure issue, not a permanent rejection");
    }

    @Test
    void enforce_failsClosed_whenRedisReturnsNull() {
        // Defence in depth: INCRBY shouldn't return null, but if a Lettuce
        // / connection-pool oddity surfaces a null we still fail-closed
        // rather than allow the transfer past an unknown total.
        when(ops.increment(any(String.class), anyLong())).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> newService().enforce(ACCOUNT, new BigDecimal("100")));
        assertTrue(ex.getMessage().contains("temporarily unavailable"));
    }

    // ---------- releaseBudget ----------

    @Test
    void releaseBudget_decrementsTheCounter_byTheAmount() {
        when(ops.increment(any(String.class), anyLong())).thenReturn(units("0"));

        newService().releaseBudget(ACCOUNT, new BigDecimal("60000"), LocalDate.of(2026, 5, 19));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> deltaCaptor = ArgumentCaptor.forClass(Long.class);
        verify(ops).increment(keyCaptor.capture(), deltaCaptor.capture());

        assertEquals("velocity:daily:" + ACCOUNT + ":2026-05-19", keyCaptor.getValue(),
                "release MUST hit the same key the original enforce did — the row's stored " +
                        "transactionDate, not LocalDate.now(), so a row inserted at 23:59 and " +
                        "failed at 00:01 still rolls back the right daily counter");
        assertEquals(-units("60000"), deltaCaptor.getValue());
    }

    @Test
    void releaseBudget_swallowsRedisFailures_doesNotThrow() {
        // markFailed must complete even if Redis hiccups. Worst case the
        // counter is artificially high until midnight; not customer-visible
        // immediately, and the audit log surfaces it for ops.
        when(ops.increment(any(String.class), anyLong()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertDoesNotThrow(() ->
                newService().releaseBudget(ACCOUNT, new BigDecimal("100"), LocalDate.now()));
    }

    @Test
    void releaseBudget_isSilentNoOp_onNullInputs() {
        newService().releaseBudget(null, new BigDecimal("1"), LocalDate.now());
        newService().releaseBudget(ACCOUNT, null, LocalDate.now());
        newService().releaseBudget(ACCOUNT, new BigDecimal("1"), null);
        verifyNoInteractions(ops);
    }

    // ---------- constructor ----------

    @Test
    void constructor_refusesNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransferLimitService(BigDecimal.ZERO, PER_DAY, redis));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferLimitService(PER_TX, new BigDecimal("-1"), redis));
    }
}
