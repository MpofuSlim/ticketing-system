package innbucks.paymentservice.service;

import innbucks.paymentservice.entity.TransactionStatus;
import innbucks.paymentservice.repository.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TransferLimitServiceTest {

    private static final BigDecimal PER_TX = new BigDecimal("100000");
    private static final BigDecimal PER_DAY = new BigDecimal("500000");
    private static final String ACCOUNT = "A000001";

    private static TransferLimitService newService(TransactionRepository repo) {
        return new TransferLimitService(PER_TX, PER_DAY, repo);
    }

    @Test
    void enforce_passes_whenAmountIsBelowBothCaps_andDailyTotalHasRoom() {
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.sumByAccountAndDateAndStatusIn(eq(ACCOUNT), any(LocalDate.class), any()))
                .thenReturn(new BigDecimal("50000"));

        // 50,000 already today + 30,000 new = 80,000 (well under 500,000 cap)
        // and 30,000 is well under per-tx cap of 100,000.
        assertDoesNotThrow(() -> newService(repo).enforce(ACCOUNT, new BigDecimal("30000")));
    }

    @Test
    void enforce_throws_whenAmountExceedsPerTransactionCap() {
        // Single-shot guard. A typo turning "1000" into "1000000" lands
        // here before the daily-sum check — no DB hit needed.
        TransactionRepository repo = mock(TransactionRepository.class);
        TransferLimitService svc = newService(repo);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.enforce(ACCOUNT, new BigDecimal("100001")));
        assertTrue(ex.getMessage().contains("Per-transaction limit"));
        // Confirm we short-circuited before hitting the ledger.
        verifyNoInteractions(repo);
    }

    @Test
    void enforce_throws_whenTodaySoFarPlusAmountExceedsDailyCap() {
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.sumByAccountAndDateAndStatusIn(eq(ACCOUNT), any(LocalDate.class), any()))
                .thenReturn(new BigDecimal("450000"));

        // 450k today + 60k new = 510k > 500k cap. Per-tx OK (60k < 100k).
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> newService(repo).enforce(ACCOUNT, new BigDecimal("60000")));
        assertTrue(ex.getMessage().contains("Daily limit exceeded"));
        assertTrue(ex.getMessage().contains("500000"), "message must expose the cap");
        assertTrue(ex.getMessage().contains("450000"), "message must expose today's running total");
    }

    @Test
    void enforce_passesAtExactBoundary_butThrowsOneShillingOver() {
        // BigDecimal.compareTo: 500000 > 500000 is false, 500001 > 500000 is true.
        // Boundary semantics: equal-to-the-cap is allowed. Tests pin this so
        // a refactor to ">=" doesn't silently flip the semantics.
        TransactionRepository repoExact = mock(TransactionRepository.class);
        when(repoExact.sumByAccountAndDateAndStatusIn(eq(ACCOUNT), any(LocalDate.class), any()))
                .thenReturn(new BigDecimal("400000"));
        // 400k + 100k = 500k -> EXACTLY at cap, allowed.
        assertDoesNotThrow(() -> newService(repoExact).enforce(ACCOUNT, new BigDecimal("100000")));

        TransactionRepository repoOver = mock(TransactionRepository.class);
        when(repoOver.sumByAccountAndDateAndStatusIn(eq(ACCOUNT), any(LocalDate.class), any()))
                .thenReturn(new BigDecimal("400000"));
        // 400k + 100,001 = 500,001 -> over, but wait per-tx cap is 100k so
        // 100,001 hits the per-tx gate first. Use an amount that's per-tx-OK
        // but pushes daily over by 0.01.
        TransactionRepository repoCent = mock(TransactionRepository.class);
        when(repoCent.sumByAccountAndDateAndStatusIn(eq(ACCOUNT), any(LocalDate.class), any()))
                .thenReturn(new BigDecimal("499999.99"));
        assertThrows(IllegalArgumentException.class,
                () -> newService(repoCent).enforce(ACCOUNT, new BigDecimal("0.02")));
    }

    @Test
    void enforce_treatsNullSumAsZero_forFirstTransactionOfTheDay() {
        // The JPQL COALESCE(SUM(...), 0) is the production safeguard, but
        // a mocked repo returning null shouldn't NPE inside the service
        // either. Tests pin that the service tolerates null too.
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.sumByAccountAndDateAndStatusIn(eq(ACCOUNT), any(LocalDate.class), any()))
                .thenReturn(null);

        assertDoesNotThrow(() -> newService(repo).enforce(ACCOUNT, new BigDecimal("100000")));
    }

    @Test
    void enforce_queriesByAccountAndTodaysDate_withCountedStatusesOnly() {
        // Pin the contract that FAILED rows DON'T count against the cap
        // (those didn't move money) while PENDING + SUCCEEDED do (PENDING
        // means we attempted; outcome unknown, must conservatively count
        // so a parallel-request attacker can't race past the limit).
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.sumByAccountAndDateAndStatusIn(any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        newService(repo).enforce(ACCOUNT, new BigDecimal("1"));

        verify(repo).sumByAccountAndDateAndStatusIn(
                eq(ACCOUNT),
                eq(LocalDate.now()),
                eq(Set.of(TransactionStatus.PENDING, TransactionStatus.SUCCEEDED)));
    }

    @Test
    void constructor_refusesNonPositiveLimits() {
        TransactionRepository repo = mock(TransactionRepository.class);
        assertThrows(IllegalArgumentException.class,
                () -> new TransferLimitService(BigDecimal.ZERO, PER_DAY, repo));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferLimitService(PER_TX, new BigDecimal("-1"), repo));
    }
}
