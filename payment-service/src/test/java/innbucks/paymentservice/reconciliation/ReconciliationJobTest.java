package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.entity.TransactionStatus;
import innbucks.paymentservice.entity.TransactionType;
import innbucks.paymentservice.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReconciliationJobTest {

    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    private static Transaction stalePending(TransactionType type, Instant createdAt) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .transactionType(type)
                .status(TransactionStatus.PENDING)
                .customerPhone("+254777224008")
                .sourceAccountId("A000001")
                .destinationAccountId("A000002")
                .amount(new BigDecimal("100.00"))
                .transactionDate(LocalDate.now())
                .createdAt(createdAt)
                .build();
    }

    private static ReconciliationJob newJob(TransactionRepository repo, PaymentMetrics metrics, int batchSize) {
        return new ReconciliationJob(repo, metrics, FIVE_MINUTES, batchSize);
    }

    @Test
    void scan_flagsEachStaleRowOnceWithMetricAndLog() {
        TransactionRepository repo = mock(TransactionRepository.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());

        Transaction old1 = stalePending(TransactionType.TRANSFER, Instant.now().minus(Duration.ofMinutes(10)));
        Transaction old2 = stalePending(TransactionType.WITHDRAWAL, Instant.now().minus(Duration.ofMinutes(7)));
        when(repo.findStalePending(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(old1, old2));

        newJob(repo, metrics, 100).scan();

        // One counter increment per row, tagged by transaction type so dashboards
        // can split TRANSFER vs WITHDRAWAL drift.
        assertEquals(1.0, metrics_count(metrics, "TRANSFER"));
        assertEquals(1.0, metrics_count(metrics, "WITHDRAWAL"));
    }

    @Test
    void scan_queriesWithCorrectCutoff_derivedFromStaleThreshold() {
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findStalePending(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        Instant before = Instant.now();
        newJob(repo, new PaymentMetrics(new SimpleMeterRegistry()), 100).scan();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).findStalePending(cutoff.capture(), any(Pageable.class));
        Instant capturedCutoff = cutoff.getValue();

        // Cutoff = now - threshold. Allow a tiny window because the call
        // crosses the clock between `before` and `after`.
        assertFalse(capturedCutoff.isAfter(after.minus(FIVE_MINUTES)),
                "cutoff must not be later than (now - threshold)");
        assertFalse(capturedCutoff.isBefore(before.minus(FIVE_MINUTES)),
                "cutoff must not be earlier than (now - threshold)");
    }

    @Test
    void scan_doesNothing_whenLedgerHasNoStalePendingRows() {
        TransactionRepository repo = mock(TransactionRepository.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        when(repo.findStalePending(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        newJob(repo, metrics, 100).scan();

        // No counter increment, no log noise — the happy steady-state case.
        assertEquals(0.0, metrics_count(metrics, "TRANSFER"));
        assertEquals(0.0, metrics_count(metrics, "WITHDRAWAL"));
    }

    @Test
    void scan_paginatesWithTheConfiguredBatchSize() {
        // Long-broken system protection: a million stuck PENDING rows
        // would otherwise be loaded in one go.
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findStalePending(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        newJob(repo, new PaymentMetrics(new SimpleMeterRegistry()), 250).scan();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findStalePending(any(Instant.class), pageable.capture());
        assertEquals(250, pageable.getValue().getPageSize());
        assertEquals(0, pageable.getValue().getPageNumber(),
                "scan always asks for page 0 — next scan picks up what's behind it");
    }

    @Test
    void scan_doesNotMutateTheRow_v1IsObserveOnly() {
        // Auto-flipping PENDING -> FAILED would be wrong: a stale PENDING
        // might be a successful Oradian transfer we just failed to mark.
        // Flipping would tell the customer the money never moved.
        // Resolution is operator-driven; this test pins that the scan
        // never calls save() / delete().
        TransactionRepository repo = mock(TransactionRepository.class);
        Transaction stuck = stalePending(TransactionType.TRANSFER, Instant.now().minus(Duration.ofHours(1)));
        when(repo.findStalePending(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(stuck));

        newJob(repo, new PaymentMetrics(new SimpleMeterRegistry()), 100).scan();

        verify(repo, never()).save(any());
        verify(repo, never()).delete(any());
        verify(repo, never()).deleteById(any());
    }

    /** Reads the Micrometer counter value for one transaction-type tag. */
    private static double metrics_count(PaymentMetrics metrics, String type) {
        // The PaymentMetrics class registers via Counter.builder().register(reg) —
        // re-registering with the same name + tags returns the existing counter
        // so we can re-discover its value here.
        SimpleMeterRegistry reg = (SimpleMeterRegistry) extractRegistry(metrics);
        return reg.find("payment.transactions.stale_pending")
                .tag("type", type)
                .counters()
                .stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private static io.micrometer.core.instrument.MeterRegistry extractRegistry(PaymentMetrics metrics) {
        try {
            var field = PaymentMetrics.class.getDeclaredField("registry");
            field.setAccessible(true);
            return (io.micrometer.core.instrument.MeterRegistry) field.get(metrics);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
