package innbucks.paymentservice.controller;

import innbucks.paymentservice.controller.PaymentOpsController.AgeBucket;
import innbucks.paymentservice.controller.PaymentOpsController.ExceptionQueue;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.ReconRun;
import innbucks.paymentservice.reconciliation.SettlementReconciliationJob;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.repository.ReconRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the internal ops surface: the X-Internal-Token gate (constant-time,
 * 401 on missing/wrong — per the house internal-endpoint rule the assertion
 * is the SPECIFIC code, never is4xxClientError), the workbasket's ageing
 * buckets, and the manual recon trigger's delegation + unconfigured 422.
 */
class PaymentOpsControllerTest {

    private static final String TOKEN = "test-internal-token";

    private PaymentRepository payments;
    private ReconRunRepository runs;
    private SettlementReconciliationJob job;
    private PaymentOpsController controller;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        runs = mock(ReconRunRepository.class);
        job = mock(SettlementReconciliationJob.class);
        controller = new PaymentOpsController(payments, runs, job, TOKEN);
    }

    private static Payment aged(PaymentStatus status, Duration age) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(UUID.randomUUID())
                .customerMsisdn("+263782606983")
                .amount(new BigDecimal("40.00"))
                .currency("USD")
                .status(status)
                .codeAuthNumber("1616800")
                .createdAt(Instant.now().minus(age))
                .build();
    }

    @Test
    void missingOrWrongToken_is401_onEveryEndpoint() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.exceptions(null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.exceptions("wrong").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.reconRuns(null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.runRecon("wrong", null).getStatusCode());
        verifyNoInteractions(payments, runs, job);
    }

    @Test
    void blankConfiguredToken_rejectsEvenAMatchingBlank() {
        PaymentOpsController unconfigured = new PaymentOpsController(payments, runs, job, "");
        assertEquals(HttpStatus.UNAUTHORIZED, unconfigured.exceptions("").getStatusCode());
    }

    @Test
    void exceptions_bucketsByAge_andSortsOldestFirst() {
        Payment fresh = aged(PaymentStatus.COMPLETED_UNCONFIRMED, Duration.ofMinutes(90));
        Payment overdue = aged(PaymentStatus.COMPLETED_UNCONFIRMED, Duration.ofHours(30));
        Payment escalation = aged(PaymentStatus.IN_DOUBT, Duration.ofHours(100));
        Payment stuckCode = aged(PaymentStatus.TOKEN_ISSUED, Duration.ofHours(2));
        when(payments.findByStatus(eq(PaymentStatus.COMPLETED_UNCONFIRMED), any(Pageable.class)))
                .thenReturn(List.of(fresh, overdue));
        when(payments.findByStatusAndCodeExpiresAtBefore(eq(PaymentStatus.TOKEN_ISSUED), any(), any(Pageable.class)))
                .thenReturn(List.of(stuckCode));
        when(payments.findByStatus(eq(PaymentStatus.IN_DOUBT), any(Pageable.class)))
                .thenReturn(List.of(escalation));

        ResponseEntity<ApiResult<ExceptionQueue>> resp = controller.exceptions(TOKEN);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        ExceptionQueue queue = resp.getBody().getData();
        assertEquals(4, queue.totalCount());
        assertEquals(2, queue.paidUnconfirmedCount());
        assertEquals(1, queue.stuckCodeCount());
        assertEquals(1, queue.inDoubtCount());
        assertEquals(1, queue.over72hCount());
        // Oldest first — the escalation leads the list.
        assertEquals(escalation.getPaymentReference(), queue.items().get(0).paymentReference());
        assertEquals(AgeBucket.OVER_72H, queue.items().get(0).ageBucket());
        assertEquals(AgeBucket.H24_TO_72H, byRef(queue, overdue).ageBucket());
        assertEquals(AgeBucket.UNDER_24H, byRef(queue, fresh).ageBucket());
        // MSISDN is masked for the ops screen — full numbers stay in the DB.
        assertEquals("****6983", queue.items().get(0).msisdnMasked());
    }

    @Test
    void exceptions_emptyWorkbasket_isTheGoalState() {
        when(payments.findByStatus(any(), any(Pageable.class))).thenReturn(List.of());
        when(payments.findByStatusAndCodeExpiresAtBefore(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        ResponseEntity<ApiResult<ExceptionQueue>> resp = controller.exceptions(TOKEN);

        assertEquals(0, resp.getBody().getData().totalCount());
        assertEquals("Workbasket is empty", resp.getBody().getMessage());
    }

    @Test
    void reconRuns_returnsHistoryNewestFirst() {
        ReconRun run = ReconRun.builder()
                .id(UUID.randomUUID())
                .windowStart(Instant.parse("2026-06-11T00:00:00Z"))
                .windowEnd(Instant.parse("2026-06-12T00:00:00Z"))
                .source("MINI_STATEMENT")
                .status(ReconRun.Status.CLEAN)
                .coverageComplete(true)
                .matchedCount(41).matchedAmountCents(164000)
                .build();
        when(runs.findTop30ByOrderByCreatedAtDesc()).thenReturn(List.of(run));

        ResponseEntity<ApiResult<List<ReconRun>>> resp = controller.reconRuns(TOKEN);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().getData().size());
        assertEquals(ReconRun.Status.CLEAN, resp.getBody().getData().get(0).getStatus());
    }

    @Test
    void runRecon_defaultsToYesterday_andReturnsThePersistedRun() {
        ReconRun run = ReconRun.builder().id(UUID.randomUUID())
                .status(ReconRun.Status.DISCREPANT).build();
        when(job.runFor(any(LocalDate.class))).thenReturn(run);

        ResponseEntity<ApiResult<ReconRun>> resp = controller.runRecon(TOKEN, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("DISCREPANT"));
        verify(job).runFor(LocalDate.now(ZoneOffset.UTC).minusDays(1));
    }

    @Test
    void runRecon_explicitDate_isPassedThrough() {
        when(job.runFor(LocalDate.of(2026, 6, 10)))
                .thenReturn(ReconRun.builder().id(UUID.randomUUID()).status(ReconRun.Status.CLEAN).build());

        ResponseEntity<ApiResult<ReconRun>> resp = controller.runRecon(TOKEN, "2026-06-10");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(job).runFor(LocalDate.of(2026, 6, 10));
    }

    @Test
    void runRecon_unconfigured_is422() {
        when(job.runFor(any(LocalDate.class))).thenReturn(null);

        ResponseEntity<ApiResult<ReconRun>> resp = controller.runRecon(TOKEN, null);

        assertEquals(422, resp.getStatusCode().value());
        assertTrue(resp.getBody().getMessage().contains("PAYMENTS_INNBUCKS_MERCHANT_ACCOUNT"));
    }

    private static PaymentOpsController.ExceptionItem byRef(ExceptionQueue queue, Payment p) {
        return queue.items().stream()
                .filter(i -> i.paymentReference().equals(p.getPaymentReference()))
                .findFirst().orElseThrow();
    }
}
