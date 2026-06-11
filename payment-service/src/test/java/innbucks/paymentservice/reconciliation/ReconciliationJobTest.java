package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.client.BankApiClient;
import innbucks.paymentservice.client.BankInquiryResult;
import innbucks.paymentservice.client.BookingServiceClient;
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
                mock(BankApiClient.class), metrics, FIVE_MINUTES, batchSize);
    }

    private static ReconciliationJob newPaymentJob(PaymentRepository payments,
                                                   PaymentRecordService records,
                                                   BookingServiceClient bookings,
                                                   PaymentMetrics metrics) {
        // Bank API unconfigured (isConfigured() -> false by Mockito default):
        // the staleness sweep stays observe-only in these tests.
        return new ReconciliationJob(mock(TransactionRepository.class), payments,
                records, bookings, mock(BankApiClient.class), metrics, FIVE_MINUTES, 100);
    }

    private static ReconciliationJob newInquiryJob(PaymentRepository payments,
                                                   PaymentRecordService records,
                                                   BankApiClient bankApi,
                                                   PaymentMetrics metrics) {
        return new ReconciliationJob(mock(TransactionRepository.class), payments,
                records, mock(BookingServiceClient.class), bankApi, metrics, FIVE_MINUTES, 100);
    }

    private static Payment paymentRow(Payment.PaymentStatus status, Instant createdAt) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(UUID.randomUUID())
                .customerMsisdn("+263770000001")
                .customerAccount("CUST-1")
                .merchantAccount("MERCH-1")
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .status(status)
                .createdAt(createdAt)
                .build();
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

    // ---- ticket-payment ledger sweeps --------------------------------------

    @Test
    void scanPayments_flagsStalePendingAndInDoubt_observeOnly() {
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
        // Observe-only: the sweep must never resolve or fail a row by itself —
        // an IN_DOUBT flip without querying the processor would be a guess.
        verifyNoInteractions(records);
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

    // ---- inquiry-based resolution of stale rows (bank configured) ----------

    @Test
    void inquiryCompleted_parksRowAsCompletedUnconfirmed() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        BankApiClient bankApi = mock(BankApiClient.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment inDoubt = paymentRow(Payment.PaymentStatus.IN_DOUBT, Instant.now().minus(Duration.ofMinutes(9)));
        when(payments.findByStatusInAndCreatedAtBefore(any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(inDoubt));
        when(bankApi.isConfigured()).thenReturn(true);
        when(bankApi.inquireTransaction(eq("CUST-1"), eq(inDoubt.getPaymentReference())))
                .thenReturn(new BankInquiryResult(BankInquiryResult.Outcome.COMPLETED, "BANK-REF-7", "SUCCESS", null));

        newInquiryJob(payments, records, bankApi, metrics).scanPayments();

        // Money moved upstream: park as COMPLETED_UNCONFIRMED (the
        // confirm-retry loop promotes to SUCCEEDED on the next pass).
        verify(records).markCompletedUnconfirmed(eq(inDoubt.getId()), eq("BANK-REF-7"), any());
        verify(records, never()).markFailed(any(), any(), any());
    }

    @Test
    void inquiryFailedOrNotFound_closesRowFailed() {
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        BankApiClient bankApi = mock(BankApiClient.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment failedUpstream = paymentRow(Payment.PaymentStatus.IN_DOUBT, Instant.now().minus(Duration.ofMinutes(9)));
        Payment neverLanded = paymentRow(Payment.PaymentStatus.PENDING, Instant.now().minus(Duration.ofMinutes(7)));
        when(payments.findByStatusInAndCreatedAtBefore(any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(failedUpstream, neverLanded));
        when(bankApi.isConfigured()).thenReturn(true);
        when(bankApi.inquireTransaction(eq("CUST-1"), eq(failedUpstream.getPaymentReference())))
                .thenReturn(new BankInquiryResult(BankInquiryResult.Outcome.FAILED, null, "DECLINED", "card declined"));
        when(bankApi.inquireTransaction(eq("CUST-1"), eq(neverLanded.getPaymentReference())))
                .thenReturn(new BankInquiryResult(BankInquiryResult.Outcome.NOT_FOUND, null, "404", null));

        newInquiryJob(payments, records, bankApi, metrics).scanPayments();

        verify(records).markFailed(eq(failedUpstream.getId()), eq("DECLINED"), eq("card declined"));
        verify(records).markFailed(eq(neverLanded.getId()), eq("not_found_upstream"), any());
        verify(records, never()).markCompletedUnconfirmed(any(), any(), any());
    }

    @Test
    void inquiryUnknownOrError_leavesRowUntouched() {
        // NEVER guess: an unclassifiable inquiry (or an inquiry failure)
        // leaves the row for the next sweep.
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        BankApiClient bankApi = mock(BankApiClient.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Payment unknown = paymentRow(Payment.PaymentStatus.IN_DOUBT, Instant.now().minus(Duration.ofMinutes(9)));
        Payment erroring = paymentRow(Payment.PaymentStatus.IN_DOUBT, Instant.now().minus(Duration.ofMinutes(8)));
        when(payments.findByStatusInAndCreatedAtBefore(any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(unknown, erroring));
        when(bankApi.isConfigured()).thenReturn(true);
        when(bankApi.inquireTransaction(eq("CUST-1"), eq(unknown.getPaymentReference())))
                .thenReturn(new BankInquiryResult(BankInquiryResult.Outcome.UNKNOWN, null, null, null));
        when(bankApi.inquireTransaction(eq("CUST-1"), eq(erroring.getPaymentReference())))
                .thenThrow(new RuntimeException("inquiry timeout"));

        newInquiryJob(payments, records, bankApi, metrics).scanPayments();

        verify(records, never()).markFailed(any(), any(), any());
        verify(records, never()).markCompletedUnconfirmed(any(), any(), any());
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
