package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.CodeStatusResult;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.entity.TransactionStatus;
import innbucks.paymentservice.entity.TransactionType;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.repository.TransactionRepository;
import innbucks.paymentservice.service.PaymentRecordService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
        // Payment-side collaborators are inert mocks here: Mockito's default
        // answers return empty lists / false, so the payment sweeps are
        // no-ops in the transactions-ledger tests.
        return new ReconciliationJob(repo, mock(PaymentRepository.class),
                mock(PaymentRecordService.class), mock(BookingServiceClient.class),
                mock(InnbucksApiClient.class), metrics,
                mock(innbucks.paymentservice.service.CodePaymentResolutionService.class),
                FIVE_MINUTES, batchSize);
    }

    private static ReconciliationJob newPaymentJob(PaymentRepository payments,
                                                   PaymentRecordService records,
                                                   BookingServiceClient bookings,
                                                   PaymentMetrics metrics) {
        return new ReconciliationJob(mock(TransactionRepository.class), payments,
                records, bookings, mock(InnbucksApiClient.class), metrics,
                new innbucks.paymentservice.service.CodePaymentResolutionService(records, bookings, metrics),
                FIVE_MINUTES, 100);
    }

    private static ReconciliationJob newPollJob(PaymentRepository payments,
                                                PaymentRecordService records,
                                                BookingServiceClient bookings,
                                                InnbucksApiClient innbucksApi,
                                                PaymentMetrics metrics) {
        // REAL resolution service over the same mocks: poll tests keep
        // verifying confirm/markSucceeded/metrics exactly as before the extract.
        return new ReconciliationJob(mock(TransactionRepository.class), payments,
                records, bookings, innbucksApi, metrics,
                new innbucks.paymentservice.service.CodePaymentResolutionService(records, bookings, metrics),
                FIVE_MINUTES, 100);
    }

    private static Payment paymentRow(Payment.PaymentStatus status, Instant createdAt) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(UUID.randomUUID())
                .customerMsisdn("+263770000001")
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .status(status)
                .createdAt(createdAt)
                .build();
    }

    /** TOKEN_ISSUED row with code handles, expiring at the given instant. */
    private static Payment tokenIssuedRow(Instant expiresAt) {
        Payment p = paymentRow(Payment.PaymentStatus.TOKEN_ISSUED, Instant.now().minus(Duration.ofMinutes(1)));
        p.setInnbucksCode("701285660");
        p.setCodeAuthNumber("1616800");
        p.setCodeExpiresAt(expiresAt);
        return p;
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

    @Test
    void scanPayments_stalePending_isClosedFailed_inDoubtObserveOnly() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment pending = paymentRow(Payment.PaymentStatus.PENDING, Instant.now().minus(Duration.ofMinutes(10)));
        Payment inDoubt = paymentRow(Payment.PaymentStatus.IN_DOUBT, Instant.now().minus(Duration.ofMinutes(8)));
        when(payments.findByStatusInAndCreatedAtBefore(any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(pending, inDoubt));

        newPaymentJob(payments, records, mock(BookingServiceClient.class), metrics).scanPayments();

        assertEquals(1.0, paymentStaleCount(metrics, "PENDING"));
        assertEquals(1.0, paymentStaleCount(metrics, "IN_DOUBT"));
        // Stale PENDING in the code flow = we died before recording the
        // generate outcome; the code (if any) was never DELIVERED, so closing
        // FAILED is safe and frees the booking slot.
        verify(records).markFailed(eq(pending.getId()), eq("stale_pending"), anyString());
        // IN_DOUBT has no writer in the code flow — legacy rows are operator
        // territory, never auto-resolved.
        verify(records, never()).markFailed(eq(inDoubt.getId()), anyString(), anyString());
        verify(records, never()).markExpired(any(), anyString());
    }

    @Test
    void scanPayments_retriesUnconfirmed_andResolvesOnSuccess() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        BookingServiceClient bookings = mock(BookingServiceClient.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment unconfirmed = paymentRow(Payment.PaymentStatus.COMPLETED_UNCONFIRMED,
                Instant.now().minus(Duration.ofMinutes(3)));
        when(payments.findByStatus(eq(Payment.PaymentStatus.COMPLETED_UNCONFIRMED), any(Pageable.class)))
                .thenReturn(List.of(unconfirmed));
        when(bookings.confirmBooking(unconfirmed.getBookingId()))
                .thenReturn(java.util.Map.of("confirmationNumber", "INN-CONF-7"));

        newPaymentJob(payments, records, bookings, metrics).scanPayments();

        verify(records).resolveUnconfirmed(unconfirmed.getId(), "INN-CONF-7");
        assertEquals(1.0, unconfirmedRetryCount(metrics, "resolved"));
    }

    @Test
    void scanPayments_unconfirmedRetryFailures_areIsolatedPerRow() {
        // One booking's persistent rejection must not stop the rest of the
        // queue from healing.
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        BookingServiceClient bookings = mock(BookingServiceClient.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment broken = paymentRow(Payment.PaymentStatus.COMPLETED_UNCONFIRMED,
                Instant.now().minus(Duration.ofMinutes(9)));
        Payment healable = paymentRow(Payment.PaymentStatus.COMPLETED_UNCONFIRMED,
                Instant.now().minus(Duration.ofMinutes(4)));
        when(payments.findByStatus(eq(Payment.PaymentStatus.COMPLETED_UNCONFIRMED), any(Pageable.class)))
                .thenReturn(List.of(broken, healable));
        when(bookings.confirmBooking(broken.getBookingId()))
                .thenThrow(new BookingServiceClient.BookingConfirmationException("hold gone for good", 409));
        when(bookings.confirmBooking(healable.getBookingId()))
                .thenReturn(java.util.Map.of("confirmationNumber", "INN-CONF-8"));

        newPaymentJob(payments, records, bookings, metrics).scanPayments();

        verify(records).resolveUnconfirmed(healable.getId(), "INN-CONF-8");
        verify(records, never()).resolveUnconfirmed(eq(broken.getId()), any());
        assertEquals(1.0, unconfirmedRetryCount(metrics, "resolved"));
        assertEquals(1.0, unconfirmedRetryCount(metrics, "still_failing"));
    }

    private static CodeStatusResult status(CodeStatusResult.Status s) {
        return new CodeStatusResult(s, s.name(), null);
    }

    @Test
    void poll_paidCode_confirmsBooking_andMarksSucceeded() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        BookingServiceClient bookings = mock(BookingServiceClient.class);
        InnbucksApiClient innbucksApi = mock(InnbucksApiClient.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment open = tokenIssuedRow(Instant.now().plus(Duration.ofMinutes(8)));
        when(payments.findByStatus(eq(Payment.PaymentStatus.TOKEN_ISSUED), any(Pageable.class)))
                .thenReturn(List.of(open));
        when(innbucksApi.isConfigured()).thenReturn(true);
        when(innbucksApi.inquireCodeStatus("701285660")).thenReturn(status(CodeStatusResult.Status.PAID));
        when(bookings.confirmBooking(open.getBookingId()))
                .thenReturn(java.util.Map.of("confirmationNumber", "INN-CONF-9"));

        newPollJob(payments, records, bookings, innbucksApi, metrics).pollCodePayments();

        verify(records).markSucceeded(open.getId(), "1616800", "INN-CONF-9");
        assertEquals(1.0, codeResolutionCount(metrics, "paid"));
    }

    @Test
    void poll_claimedCode_isTreatedAsPaid_perTheDoc() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        BookingServiceClient bookings = mock(BookingServiceClient.class);
        InnbucksApiClient innbucksApi = mock(InnbucksApiClient.class);
        Payment open = tokenIssuedRow(Instant.now().plus(Duration.ofMinutes(8)));
        when(payments.findByStatus(eq(Payment.PaymentStatus.TOKEN_ISSUED), any(Pageable.class)))
                .thenReturn(List.of(open));
        when(innbucksApi.isConfigured()).thenReturn(true);
        when(innbucksApi.inquireCodeStatus("701285660")).thenReturn(status(CodeStatusResult.Status.CLAIMED));
        when(bookings.confirmBooking(open.getBookingId()))
                .thenReturn(java.util.Map.of("confirmationNumber", "INN-CONF-10"));

        newPollJob(payments, records, bookings, innbucksApi, new PaymentMetrics(new SimpleMeterRegistry()))
                .pollCodePayments();

        verify(records).markSucceeded(open.getId(), "1616800", "INN-CONF-10");
    }

    @Test
    void poll_paidCode_confirmFails_parksCompletedUnconfirmed_neverFailed() {
        // Money HAS moved (customer approved the code). A booking-confirm
        // failure must park the row for the confirm-retry loop — recording
        // FAILED here would be the one lie the ledger must never contain.
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        BookingServiceClient bookings = mock(BookingServiceClient.class);
        InnbucksApiClient innbucksApi = mock(InnbucksApiClient.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment open = tokenIssuedRow(Instant.now().plus(Duration.ofMinutes(8)));
        when(payments.findByStatus(eq(Payment.PaymentStatus.TOKEN_ISSUED), any(Pageable.class)))
                .thenReturn(List.of(open));
        when(innbucksApi.isConfigured()).thenReturn(true);
        when(innbucksApi.inquireCodeStatus("701285660")).thenReturn(status(CodeStatusResult.Status.PAID));
        when(bookings.confirmBooking(open.getBookingId()))
                .thenThrow(new BookingServiceClient.BookingConfirmationException("hold expired", 409));

        newPollJob(payments, records, bookings, innbucksApi, metrics).pollCodePayments();

        verify(records).markCompletedUnconfirmed(eq(open.getId()), eq("1616800"), contains("hold expired"));
        verify(records, never()).markFailed(any(), anyString(), anyString());
        verify(records, never()).markExpired(any(), anyString());
        assertEquals(1.0, codeResolutionCount(metrics, "paid_unconfirmed"));
    }

    @Test
    void poll_expiredOrTimedOut_marksExpired_freeingTheSlot() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        InnbucksApiClient innbucksApi = mock(InnbucksApiClient.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment expired = tokenIssuedRow(Instant.now().minus(Duration.ofMinutes(1)));
        Payment timedOut = tokenIssuedRow(Instant.now().minus(Duration.ofMinutes(2)));
        when(payments.findByStatus(eq(Payment.PaymentStatus.TOKEN_ISSUED), any(Pageable.class)))
                .thenReturn(List.of(expired, timedOut));
        when(innbucksApi.isConfigured()).thenReturn(true);
        when(innbucksApi.inquireCodeStatus(expired.getInnbucksCode()))
                .thenReturn(status(CodeStatusResult.Status.EXPIRED))
                .thenReturn(status(CodeStatusResult.Status.TIMED_OUT));

        newPollJob(payments, records, mock(BookingServiceClient.class), innbucksApi, metrics)
                .pollCodePayments();

        verify(records, times(2)).markExpired(any(UUID.class), anyString());
        assertEquals(2.0, codeResolutionCount(metrics, "expired"));
    }

    @Test
    void poll_stillNew_beforeDeadline_leavesRowAlone() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        InnbucksApiClient innbucksApi = mock(InnbucksApiClient.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment open = tokenIssuedRow(Instant.now().plus(Duration.ofMinutes(8)));
        when(payments.findByStatus(eq(Payment.PaymentStatus.TOKEN_ISSUED), any(Pageable.class)))
                .thenReturn(List.of(open));
        when(innbucksApi.isConfigured()).thenReturn(true);
        when(innbucksApi.inquireCodeStatus("701285660")).thenReturn(status(CodeStatusResult.Status.NEW));

        newPollJob(payments, records, mock(BookingServiceClient.class), innbucksApi, metrics)
                .pollCodePayments();

        verify(records, never()).markExpired(any(), anyString());
        assertEquals(1.0, codeResolutionCount(metrics, "still_pending"));
    }

    @Test
    void poll_stillNew_pastDeadlinePlusGrace_expiresLocally() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        InnbucksApiClient innbucksApi = mock(InnbucksApiClient.class);
        // Deadline 10 minutes ago — far past the 2-minute grace.
        Payment open = tokenIssuedRow(Instant.now().minus(Duration.ofMinutes(10)));
        when(payments.findByStatus(eq(Payment.PaymentStatus.TOKEN_ISSUED), any(Pageable.class)))
                .thenReturn(List.of(open));
        when(innbucksApi.isConfigured()).thenReturn(true);
        when(innbucksApi.inquireCodeStatus("701285660")).thenReturn(status(CodeStatusResult.Status.NEW));

        newPollJob(payments, records, mock(BookingServiceClient.class), innbucksApi,
                new PaymentMetrics(new SimpleMeterRegistry())).pollCodePayments();

        // Safe to expire: upstream POSITIVELY says still-New (unpaid).
        verify(records).markExpired(eq(open.getId()), contains("New"));
    }

    @Test
    void poll_unknownOrError_neverExpiresTheRow() {
        // NEVER guess: the customer may have paid. Expiring would free the
        // slot and invite a double charge — the safe failure is a blocked
        // slot + a dripping metric for the operator.
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        InnbucksApiClient innbucksApi = mock(InnbucksApiClient.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment unknown = tokenIssuedRow(Instant.now().minus(Duration.ofHours(2)));
        Payment erroring = tokenIssuedRow(Instant.now().minus(Duration.ofHours(3)));
        when(payments.findByStatus(eq(Payment.PaymentStatus.TOKEN_ISSUED), any(Pageable.class)))
                .thenReturn(List.of(unknown, erroring));
        when(innbucksApi.isConfigured()).thenReturn(true);
        when(innbucksApi.inquireCodeStatus(unknown.getInnbucksCode()))
                .thenReturn(status(CodeStatusResult.Status.UNKNOWN))
                .thenThrow(new RuntimeException("query timeout"));

        newPollJob(payments, records, mock(BookingServiceClient.class), innbucksApi, metrics)
                .pollCodePayments();

        verify(records, never()).markExpired(any(), anyString());
        verify(records, never()).markFailed(any(), anyString(), anyString());
        assertEquals(1.0, codeResolutionCount(metrics, "unknown"));
        assertEquals(1.0, codeResolutionCount(metrics, "error"));
    }

    @Test
    void poll_unconfiguredClient_neverQueries_andLeavesRows() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        InnbucksApiClient innbucksApi = mock(InnbucksApiClient.class);
        Payment open = tokenIssuedRow(Instant.now().plus(Duration.ofMinutes(8)));
        when(payments.findByStatus(eq(Payment.PaymentStatus.TOKEN_ISSUED), any(Pageable.class)))
                .thenReturn(List.of(open));
        when(innbucksApi.isConfigured()).thenReturn(false);

        newPollJob(payments, records, mock(BookingServiceClient.class), innbucksApi,
                new PaymentMetrics(new SimpleMeterRegistry())).pollCodePayments();

        verify(innbucksApi, never()).inquireCodeStatus(anyString());
        verifyNoInteractions(records);
    }

    private static double paymentStaleCount(PaymentMetrics metrics, String status) {
        SimpleMeterRegistry reg = (SimpleMeterRegistry) extractRegistry(metrics);
        return reg.find("payment.payments.stale").tag("status", status)
                .counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private static double unconfirmedRetryCount(PaymentMetrics metrics, String outcome) {
        SimpleMeterRegistry reg = (SimpleMeterRegistry) extractRegistry(metrics);
        return reg.find("payment.payments.unconfirmed_retry").tag("outcome", outcome)
                .counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private static double codeResolutionCount(PaymentMetrics metrics, String outcome) {
        SimpleMeterRegistry reg = (SimpleMeterRegistry) extractRegistry(metrics);
        return reg.find("payment.payments.code_resolution").tag("outcome", outcome)
                .counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
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
