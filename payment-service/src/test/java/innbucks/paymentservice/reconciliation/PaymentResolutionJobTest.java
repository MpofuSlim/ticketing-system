package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.CodeStatusResult;
import innbucks.paymentservice.client.EventServiceClient;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.client.ZimswitchProperties;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.BookingOrderGateway;
import innbucks.paymentservice.order.OrderGatewayRegistry;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.CodePaymentResolutionService;
import innbucks.paymentservice.service.PaymentRecordService;
import innbucks.paymentservice.service.ZimswitchCardPaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentResolutionJobTest {

    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    /** Registry over a REAL BookingOrderGateway so gateway-mediated confirms
     *  still land on the mocked BookingServiceClient exactly as before. */
    private static OrderGatewayRegistry registryOver(BookingServiceClient bookings) {
        return new OrderGatewayRegistry(List.of(
                new BookingOrderGateway(
                        bookings, mock(EventServiceClient.class),
                        "USD", Duration.ofMinutes(10))));
    }

    private static PaymentResolutionJob newPaymentJob(PaymentRepository payments,
                                                      PaymentRecordService records,
                                                      BookingServiceClient bookings,
                                                      PaymentMetrics metrics) {
        return new PaymentResolutionJob(payments,
                records, mock(InnbucksApiClient.class), metrics,
                new CodePaymentResolutionService(records, registryOver(bookings), metrics),
                mock(ZimswitchCardPaymentService.class),
                mock(UnconfirmedPaymentAlerter.class),
                new ZimswitchProperties(),
                FIVE_MINUTES, 100);
    }

    private static PaymentResolutionJob newPollJob(PaymentRepository payments,
                                                   PaymentRecordService records,
                                                   BookingServiceClient bookings,
                                                   InnbucksApiClient innbucksApi,
                                                   PaymentMetrics metrics) {
        // REAL resolution service over the same mocks: poll tests keep
        // verifying confirm/markSucceeded/metrics exactly as before the extract.
        return new PaymentResolutionJob(payments,
                records, innbucksApi, metrics,
                new CodePaymentResolutionService(records, registryOver(bookings), metrics),
                mock(ZimswitchCardPaymentService.class),
                mock(UnconfirmedPaymentAlerter.class),
                new ZimswitchProperties(),
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

    // -- The sweeps must stay SCHEDULED -------------------------------------

    /**
     * Regression guard for the outage this class was restored from.
     *
     * <p>These four sweeps previously lived in a class that also carried the
     * Oradian wallet reconciliation. Removing Oradian removed the file, and
     * every ticket payment silently stopped resolving: the repository queries,
     * the config keys and {@code CodePaymentResolutionService} all still
     * existed, so nothing failed and no test went red — there was simply
     * nothing left calling them, and customers were debited for bookings that
     * never confirmed.
     *
     * <p>Every other test here drives a poller by calling it directly, which
     * passes just as happily when nothing invokes it in production. This is the
     * only test that asserts the sweeps are actually WIRED, so it is the one
     * that would have caught the deletion.
     */
    @Test
    void everyRailSweep_isScheduled_andTheJobIsAComponent() throws Exception {
        assertNotNull(PaymentResolutionJob.class.getAnnotation(Component.class),
                "PaymentResolutionJob must stay a @Component or Spring never schedules it — "
                        + "payments would stop resolving silently");

        for (String sweep : List.of("pollCodePayments", "pollCardPayments", "scanPayments")) {
            Scheduled scheduled = PaymentResolutionJob.class
                    .getMethod(sweep).getAnnotation(Scheduled.class);
            assertNotNull(scheduled, sweep + "() must stay @Scheduled — without it, "
                    + "TOKEN_ISSUED rows are never resolved and paid customers never get their tickets");
            assertFalse(scheduled.fixedDelayString().isBlank(),
                    sweep + "() must keep a fixed-delay cadence");
        }
    }

    // -- Ledger sweeps -------------------------------------------------------

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

    // -- Code poll -----------------------------------------------------------

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
        when(payments.findByStatusAndPaymentRail(eq(Payment.PaymentStatus.TOKEN_ISSUED), eq(PaymentRail.INNBUCKS_CODE), any(Pageable.class)))
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
        when(payments.findByStatusAndPaymentRail(eq(Payment.PaymentStatus.TOKEN_ISSUED), eq(PaymentRail.INNBUCKS_CODE), any(Pageable.class)))
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
        when(payments.findByStatusAndPaymentRail(eq(Payment.PaymentStatus.TOKEN_ISSUED), eq(PaymentRail.INNBUCKS_CODE), any(Pageable.class)))
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
        when(payments.findByStatusAndPaymentRail(eq(Payment.PaymentStatus.TOKEN_ISSUED), eq(PaymentRail.INNBUCKS_CODE), any(Pageable.class)))
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
        when(payments.findByStatusAndPaymentRail(eq(Payment.PaymentStatus.TOKEN_ISSUED), eq(PaymentRail.INNBUCKS_CODE), any(Pageable.class)))
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
        when(payments.findByStatusAndPaymentRail(eq(Payment.PaymentStatus.TOKEN_ISSUED), eq(PaymentRail.INNBUCKS_CODE), any(Pageable.class)))
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
        when(payments.findByStatusAndPaymentRail(eq(Payment.PaymentStatus.TOKEN_ISSUED), eq(PaymentRail.INNBUCKS_CODE), any(Pageable.class)))
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
        when(payments.findByStatusAndPaymentRail(eq(Payment.PaymentStatus.TOKEN_ISSUED), eq(PaymentRail.INNBUCKS_CODE), any(Pageable.class)))
                .thenReturn(List.of(open));
        when(innbucksApi.isConfigured()).thenReturn(false);

        newPollJob(payments, records, mock(BookingServiceClient.class), innbucksApi,
                new PaymentMetrics(new SimpleMeterRegistry())).pollCodePayments();

        verify(innbucksApi, never()).inquireCodeStatus(anyString());
        verifyNoInteractions(records);
    }

    // -- Metric readers ------------------------------------------------------

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
