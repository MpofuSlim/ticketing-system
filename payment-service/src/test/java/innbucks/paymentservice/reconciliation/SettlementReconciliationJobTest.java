package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.client.CodeStatementEntry;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.client.InnbucksApiTransientException;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.ReconRun;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.repository.ReconRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pins the morning report's banking discipline:
 * <ul>
 *   <li>our money rows must be corroborated by a FINALISED statement code —
 *       anything else is OURS_NOT_THEIRS (our ledger may overstate);</li>
 *   <li>their finalised codes must be booked as money by us — a row stuck in
 *       TOKEN_ISSUED or missing entirely is THEIRS_NOT_OURS (customer paid,
 *       got nothing);</li>
 *   <li>amount equality is re-proven at recon time, independent of the
 *       generation-time echo guard;</li>
 *   <li>statement truncation is DETECTED (coverageComplete=false), not
 *       silently treated as full evidence;</li>
 *   <li>a statement fetch failure persists a FAILED run — recon being blind
 *       must itself be visible in the report.</li>
 * </ul>
 */
class SettlementReconciliationJobTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 11);
    private static final Instant WINDOW_START = DAY.atStartOfDay(ZoneOffset.UTC).toInstant();

    private PaymentRepository payments;
    private ReconRunRepository runs;
    private InnbucksApiClient innbucksApi;
    private PaymentMetrics metrics;
    private innbucks.paymentservice.audit.AuditService auditService;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        runs = mock(ReconRunRepository.class);
        innbucksApi = mock(InnbucksApiClient.class);
        metrics = new PaymentMetrics(new SimpleMeterRegistry());
        auditService = mock(innbucks.paymentservice.audit.AuditService.class);
        when(runs.save(any(ReconRun.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SettlementReconciliationJob job(String account) {
        return new SettlementReconciliationJob(payments, runs, innbucksApi, metrics, auditService, account);
    }

    private static Payment row(PaymentStatus status, String code, String amount) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(UUID.randomUUID())
                .customerMsisdn("+263770000001")
                .amount(new BigDecimal(amount))
                .currency("USD")
                .status(status)
                .innbucksCode(code)
                .createdAt(WINDOW_START.plusSeconds(3600))
                .build();
    }

    /** Statement entry created early in the window (full coverage). */
    private static CodeStatementEntry entry(String code, long cents, String state) {
        return new CodeStatementEntry(code, cents, state, DAY.atStartOfDay());
    }

    @Test
    void allMatched_persistsCleanRun_withTotals() {
        when(payments.findByInnbucksCodeIsNotNullAndCreatedAtBetween(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(
                        row(PaymentStatus.SUCCEEDED, "701000001", "40.00"),
                        row(PaymentStatus.COMPLETED_UNCONFIRMED, "701000002", "25.00")));
        when(innbucksApi.fetchCodeMiniStatement("2009200566693")).thenReturn(List.of(
                entry("701000001", 4000, "Paid"),
                entry("701000002", 2500, "Claimed")));

        ReconRun run = job("2009200566693").runFor(DAY);

        assertEquals(ReconRun.Status.CLEAN, run.getStatus());
        assertEquals(2, run.getMatchedCount());
        assertEquals(6500, run.getMatchedAmountCents());
        assertEquals(0, run.getOursNotTheirs());
        assertEquals(0, run.getTheirsNotOurs());
        assertTrue(run.isCoverageComplete());
        assertNull(run.getDiscrepancyDetail());
        verify(runs).save(run);
    }

    @Test
    void ourMoneyRowWithoutFinalisedStatementCode_isOursNotTheirs() {
        when(payments.findByInnbucksCodeIsNotNullAndCreatedAtBetween(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(row(PaymentStatus.SUCCEEDED, "701000001", "40.00")));
        // Their statement covers the window but has the code only as Pending —
        // NOT corroboration that money arrived.
        when(innbucksApi.fetchCodeMiniStatement(anyString())).thenReturn(List.of(
                entry("701000001", 4000, "Pending")));

        ReconRun run = job("2009200566693").runFor(DAY);

        assertEquals(ReconRun.Status.DISCREPANT, run.getStatus());
        assertEquals(1, run.getOursNotTheirs());
        assertTrue(run.getDiscrepancyDetail().contains("OURS_NOT_THEIRS"));
        assertTrue(run.getDiscrepancyDetail().contains("701000001"));
    }

    @Test
    void theirFinalisedCode_notBookedAsMoneyByUs_isTheirsNotOurs_withRowContext() {
        // Our row exists but is stuck TOKEN_ISSUED — the customer PAID and we
        // never booked it. Detail must say which row so ops can dig.
        Payment stuck = row(PaymentStatus.TOKEN_ISSUED, "701000003", "40.00");
        when(payments.findByInnbucksCodeIsNotNullAndCreatedAtBetween(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(stuck));
        when(innbucksApi.fetchCodeMiniStatement(anyString())).thenReturn(List.of(
                entry("701000003", 4000, "Paid"),
                entry("701999999", 2500, "Claimed")));

        ReconRun run = job("2009200566693").runFor(DAY);

        assertEquals(ReconRun.Status.DISCREPANT, run.getStatus());
        assertEquals(2, run.getTheirsNotOurs());
        assertTrue(run.getDiscrepancyDetail().contains(stuck.getPaymentReference()));
        assertTrue(run.getDiscrepancyDetail().contains("NO MATCHING ROW"),
                "the fully unknown code must be flagged as having no row at all");
    }

    @Test
    void matchedCode_withDifferentAmount_isAmountMismatch() {
        when(payments.findByInnbucksCodeIsNotNullAndCreatedAtBetween(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(row(PaymentStatus.SUCCEEDED, "701000004", "40.00")));
        when(innbucksApi.fetchCodeMiniStatement(anyString())).thenReturn(List.of(
                entry("701000004", 400, "Paid")));

        ReconRun run = job("2009200566693").runFor(DAY);

        assertEquals(ReconRun.Status.DISCREPANT, run.getStatus());
        assertEquals(1, run.getAmountMismatches());
        assertEquals(0, run.getMatchedCount());
        assertTrue(run.getDiscrepancyDetail().contains("ours=4000c theirs=400c"));
    }

    @Test
    void statementYoungerThanWindow_flagsIncompleteCoverage() {
        when(payments.findByInnbucksCodeIsNotNullAndCreatedAtBetween(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(row(PaymentStatus.SUCCEEDED, "701000005", "40.00")));
        // Oldest statement entry is from 18:00 — anything we created before
        // that may simply have scrolled off the recency-capped list.
        when(innbucksApi.fetchCodeMiniStatement(anyString())).thenReturn(List.of(
                new CodeStatementEntry("701888888", 1000L, "Claimed", DAY.atTime(18, 0))));

        ReconRun run = job("2009200566693").runFor(DAY);

        assertFalse(run.isCoverageComplete());
        assertTrue(run.getDiscrepancyDetail().contains("coverage incomplete"),
                "absence-of-evidence findings must be marked weak");
    }

    @Test
    void entriesOutsideTheWindow_areIgnored() {
        when(payments.findByInnbucksCodeIsNotNullAndCreatedAtBetween(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(innbucksApi.fetchCodeMiniStatement(anyString())).thenReturn(List.of(
                new CodeStatementEntry("701777777", 1000L, "Paid", DAY.minusDays(1).atTime(10, 0)),
                new CodeStatementEntry("701777778", 1000L, "Paid", DAY.plusDays(1).atTime(10, 0))));

        ReconRun run = job("2009200566693").runFor(DAY);

        assertEquals(0, run.getTheirsNotOurs(),
                "codes finalised outside the window belong to other days' runs");
    }

    @Test
    void statementFetchFailure_persistsFailedRun() {
        when(payments.findByInnbucksCodeIsNotNullAndCreatedAtBetween(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(row(PaymentStatus.SUCCEEDED, "701000006", "40.00")));
        when(innbucksApi.fetchCodeMiniStatement(anyString()))
                .thenThrow(new InnbucksApiTransientException("innbucks down", 503));

        ReconRun run = job("2009200566693").runFor(DAY);

        assertEquals(ReconRun.Status.FAILED, run.getStatus());
        assertTrue(run.getError().contains("innbucks down"));
        ArgumentCaptor<ReconRun> saved = ArgumentCaptor.forClass(ReconRun.class);
        verify(runs).save(saved.capture());
        assertEquals(ReconRun.Status.FAILED, saved.getValue().getStatus());
    }

    @Test
    void unconfiguredAccount_skipsEntirely_andPersistsNothing() {
        ReconRun run = job("  ").runFor(DAY);

        assertNull(run);
        verifyNoInteractions(innbucksApi);
        verify(runs, never()).save(any());
    }
}
