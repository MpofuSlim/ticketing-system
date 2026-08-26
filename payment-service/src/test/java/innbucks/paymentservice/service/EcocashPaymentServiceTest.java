package innbucks.paymentservice.service;

import innbucks.paymentservice.client.EcocashApiException;
import innbucks.paymentservice.client.EcocashApiTransientException;
import innbucks.paymentservice.client.EcocashChargeStatus;
import innbucks.paymentservice.client.EcocashEipClient;
import innbucks.paymentservice.client.EcocashProperties;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.ConfirmOutcome;
import innbucks.paymentservice.order.OrderGateway;
import innbucks.paymentservice.order.OrderGatewayRegistry;
import innbucks.paymentservice.order.OrderSnapshot;
import innbucks.paymentservice.order.OrderType;
import innbucks.paymentservice.service.EcocashPaymentService.EcocashCheckOutcome;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Money rules of the EcoCash rail — the parts whose failure modes are double
 * charges or goods-against-disputed-money, each pinned as its own case
 * (rationale in docs/api/ecocash-eip.md):
 *
 * <ul>
 *   <li>the row is TOKEN_ISSUED (correlator persisted) BEFORE the charge
 *       call — the inverted ordering that keeps a crashed charge resolvable
 *       while a PIN prompt may be live on the customer's phone;</li>
 *   <li>an AMBIGUOUS charge outcome is never failed and never retried — the
 *       row stays open for the Query poller;</li>
 *   <li>an echo mismatch on a COMPLETED read parks IN_DOUBT and never
 *       confirms;</li>
 *   <li>still-pending / NOT_FOUND close the row ONLY past deadline + grace;
 *       UNKNOWN never closes it.</li>
 * </ul>
 */
class EcocashPaymentServiceTest {

    private final PaymentRecordService records = mock(PaymentRecordService.class);
    private final EcocashEipClient client = mock(EcocashEipClient.class);
    private final EcocashProperties properties = new EcocashProperties();
    private final OrderGatewayRegistry gateways = mock(OrderGatewayRegistry.class);
    private final CodePaymentResolutionService resolution = mock(CodePaymentResolutionService.class);
    private final PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());

    private final EcocashPaymentService service = new EcocashPaymentService(
            records, client, properties, gateways, resolution, metrics);

    private static final String CORRELATOR = "1763385010123456";

    private static Payment openEcocashRow(Instant expiresAt) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKZ-PINKRUN26-4F3A2B1C0D9E")
                .paymentRail(PaymentRail.ECOCASH)
                .status(Payment.PaymentStatus.TOKEN_ISSUED)
                .ecocashClientCorrelator(CORRELATOR)
                .customerMsisdn("+263777222093")
                .amount(new BigDecimal("3.00"))
                .currency("USD")
                .createdAt(Instant.now().minus(Duration.ofMinutes(1)))
                .codeExpiresAt(expiresAt)
                .build();
    }

    private static EcocashChargeStatus status(EcocashChargeStatus.Outcome outcome,
                                              String total, String amount, String currency) {
        return new EcocashChargeStatus(outcome, outcome.name(), "MP251117.0952.T0527795",
                amount == null ? null : new BigDecimal(amount),
                total == null ? null : new BigDecimal(total),
                currency);
    }

    // -- startCharge ---------------------------------------------------------

    private OrderGateway payableGateway() {
        OrderGateway gateway = mock(OrderGateway.class);
        when(gateways.forType(OrderType.BOOKING)).thenReturn(gateway);
        String ref = UUID.randomUUID().toString();
        when(gateway.fetch(anyString())).thenReturn(new OrderSnapshot(
                ref, 300, "USD", "+263777222093", "PINKRUN26", "Pink Run ticket", true));
        return gateway;
    }

    @Test
    @DisplayName("the row is armed (TOKEN_ISSUED, correlator persisted) BEFORE the charge call — inverted ordering")
    void startCharge_armsRowBeforeUpstreamCall() {
        properties.setNotifyUrl("https://x.example/foundry/payments/ecocash/notify");
        when(client.canStartCharge()).thenReturn(true);
        when(client.isConfigured()).thenReturn(true);
        payableGateway();
        Payment opened = openEcocashRow(null);
        when(records.openPending(any(Payment.class))).thenReturn(opened);
        when(client.charge(anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(status(EcocashChargeStatus.Outcome.PENDING, "0.0", "3.00", "USD"));

        var outcome = service.startCharge(OrderType.BOOKING, UUID.randomUUID().toString());

        assertThat(outcome.failed()).isFalse();
        InOrder inOrder = inOrder(records, client);
        inOrder.verify(records).openPending(any(Payment.class));
        inOrder.verify(records).markEcocashChargeIssued(eq(opened.getId()), anyString(), any(Instant.class));
        inOrder.verify(client).charge(anyString(), anyString(), anyLong(), anyString(), anyString());
        // The draft row itself carries the correlator (persisted at open).
        verify(records).openPending(org.mockito.ArgumentMatchers.argThat(
                d -> d.getEcocashClientCorrelator() != null && !d.getEcocashClientCorrelator().isBlank()));
    }

    @Test
    @DisplayName("ambiguous charge outcome: row stays open for the poller — never failed, never retried")
    void startCharge_ambiguousLeavesRowOpen() {
        properties.setNotifyUrl("https://x.example/foundry/payments/ecocash/notify");
        when(client.canStartCharge()).thenReturn(true);
        when(client.isConfigured()).thenReturn(true);
        payableGateway();
        Payment opened = openEcocashRow(null);
        when(records.openPending(any(Payment.class))).thenReturn(opened);
        when(client.charge(anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new EcocashApiTransientException("read timeout", 502));

        var outcome = service.startCharge(OrderType.BOOKING, UUID.randomUUID().toString());

        // A prompt may be live on the customer's phone: the customer sees
        // PROCESSING and the Query poller resolves the truth.
        assertThat(outcome.failed()).isFalse();
        verify(records, never()).markFailed(any(), anyString(), anyString());
        verify(records).noteEvent(eq(opened.getId()), contains("ambiguous"));
    }

    @Test
    @DisplayName("active 4xx refusal: no prompt was pushed — FAILED, slot freed")
    void startCharge_refusedClosesFailed() {
        properties.setNotifyUrl("https://x.example/foundry/payments/ecocash/notify");
        when(client.canStartCharge()).thenReturn(true);
        when(client.isConfigured()).thenReturn(true);
        payableGateway();
        Payment opened = openEcocashRow(null);
        when(records.openPending(any(Payment.class))).thenReturn(opened);
        when(client.charge(anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new EcocashApiException("EcoCash refused the charge: HTTP 400", 400));

        var outcome = service.startCharge(OrderType.BOOKING, UUID.randomUUID().toString());

        assertThat(outcome.failed()).isTrue();
        verify(records).markFailed(eq(opened.getId()), eq("ecocash_refused"), anyString());
    }

    @Test
    @DisplayName("half-provisioned gate: credentials without a notify URL refuse BEFORE any ledger write")
    void startCharge_refusesWhenHalfProvisioned() {
        when(client.canStartCharge()).thenReturn(false);
        when(client.isConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.startCharge(OrderType.BOOKING, UUID.randomUUID().toString()))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessageContaining("not available");
        verify(records, never()).openPending(any());
    }

    // -- resolveOpenCharge ---------------------------------------------------

    @Test
    @DisplayName("COMPLETED with a clean echo: confirm then SUCCEEDED, EcoCash reference recorded")
    void resolve_completedConfirms() {
        Payment p = openEcocashRow(Instant.now().plus(Duration.ofMinutes(4)));
        when(client.query(p.getCustomerMsisdn(), CORRELATOR))
                .thenReturn(status(EcocashChargeStatus.Outcome.COMPLETED, "3.0", "3.0", "USD"));
        when(resolution.confirmOrder(p)).thenReturn(ConfirmOutcome.confirmed("INN-777"));

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.PAID);

        verify(records).recordEcocashReference(p.getId(), "MP251117.0952.T0527795");
        verify(records).markSucceeded(p.getId(), "MP251117.0952.T0527795", "INN-777");
        verify(records, never()).markInDoubt(any(), anyString());
    }

    @Test
    @DisplayName("COMPLETED but the confirm fails: COMPLETED_UNCONFIRMED for the shared retry sweep")
    void resolve_completedConfirmFailureParksUnconfirmed() {
        Payment p = openEcocashRow(Instant.now().plus(Duration.ofMinutes(4)));
        when(client.query(p.getCustomerMsisdn(), CORRELATOR))
                .thenReturn(status(EcocashChargeStatus.Outcome.COMPLETED, "3.0", "3.0", "USD"));
        when(resolution.confirmOrder(p)).thenReturn(ConfirmOutcome.unreachable("booking-service down"));

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.PAID);

        verify(records).markCompletedUnconfirmed(eq(p.getId()), eq("MP251117.0952.T0527795"), anyString());
        verify(records, never()).markSucceeded(any(), any(), any());
    }

    @Test
    @DisplayName("COMPLETED with an echo mismatch: parked IN_DOUBT — never confirmed, never guessed")
    void resolve_echoMismatchParksInDoubt() {
        Payment p = openEcocashRow(Instant.now().plus(Duration.ofMinutes(4)));
        when(client.query(p.getCustomerMsisdn(), CORRELATOR))
                .thenReturn(status(EcocashChargeStatus.Outcome.COMPLETED, "300.0", "300.0", "USD"));

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.PENDING);

        verify(records).markInDoubt(eq(p.getId()), contains("does not match the ledger"));
        verify(records, never()).markSucceeded(any(), any(), any());
        verify(records, never()).markCompletedUnconfirmed(any(), any(), any());
        verify(resolution, never()).confirmOrder(any());
    }

    @Test
    @DisplayName("FAILED: the positive 'subscriber rejected' answer — terminal FAILED, slot freed")
    void resolve_failedFreesSlot() {
        Payment p = openEcocashRow(Instant.now().plus(Duration.ofMinutes(4)));
        when(client.query(p.getCustomerMsisdn(), CORRELATOR))
                .thenReturn(status(EcocashChargeStatus.Outcome.FAILED, "0.0", "3.0", "USD"));

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.EXPIRED);

        verify(records).markFailed(eq(p.getId()), eq("subscriber_rejected"), anyString());
    }

    @Test
    @DisplayName("still-pending before the deadline: row left alone")
    void resolve_pendingBeforeDeadlineLeavesRow() {
        Payment p = openEcocashRow(Instant.now().plus(Duration.ofMinutes(4)));
        when(client.query(p.getCustomerMsisdn(), CORRELATOR))
                .thenReturn(status(EcocashChargeStatus.Outcome.PENDING, "0.0", "3.0", "USD"));

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.PENDING);
        verify(records, never()).markExpired(any(), anyString());
        verify(records, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("still-pending past deadline + grace: expired locally — upstream positively says unapproved")
    void resolve_pendingPastDeadlineExpires() {
        Payment p = openEcocashRow(Instant.now().minus(Duration.ofMinutes(10)));
        when(client.query(p.getCustomerMsisdn(), CORRELATOR))
                .thenReturn(status(EcocashChargeStatus.Outcome.PENDING, "0.0", "3.0", "USD"));

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.EXPIRED);
        verify(records).markExpired(eq(p.getId()), contains("closing unpaid"));
    }

    @Test
    @DisplayName("NOT_FOUND past deadline + grace: the positive 'no charge exists' answer frees the slot")
    void resolve_notFoundPastDeadlineExpires() {
        Payment p = openEcocashRow(Instant.now().minus(Duration.ofMinutes(10)));
        when(client.query(p.getCustomerMsisdn(), CORRELATOR))
                .thenReturn(new EcocashChargeStatus(EcocashChargeStatus.Outcome.NOT_FOUND,
                        "HTTP 404", null, null, null, null));

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.EXPIRED);
        verify(records).markExpired(eq(p.getId()), contains("no charge was ever created"));
    }

    @Test
    @DisplayName("NOT_FOUND before the deadline: normal post-issue latency — row left alone")
    void resolve_notFoundBeforeDeadlineLeavesRow() {
        Payment p = openEcocashRow(Instant.now().plus(Duration.ofMinutes(4)));
        when(client.query(p.getCustomerMsisdn(), CORRELATOR))
                .thenReturn(new EcocashChargeStatus(EcocashChargeStatus.Outcome.NOT_FOUND,
                        "HTTP 404", null, null, null, null));

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.PENDING);
        verify(records, never()).markExpired(any(), anyString());
    }

    @Test
    @DisplayName("UNKNOWN never closes a row — even far past the deadline (the never-guess rule)")
    void resolve_unknownNeverCloses() {
        Payment p = openEcocashRow(Instant.now().minus(Duration.ofHours(3)));
        when(client.query(p.getCustomerMsisdn(), CORRELATOR))
                .thenReturn(new EcocashChargeStatus(EcocashChargeStatus.Outcome.UNKNOWN,
                        "unparseable body", null, null, null, null));

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.PENDING);
        verify(records, never()).markExpired(any(), anyString());
        verify(records, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("query errors leave the row for the next pass")
    void resolve_queryErrorLeavesRow() {
        Payment p = openEcocashRow(Instant.now().minus(Duration.ofHours(3)));
        when(client.query(p.getCustomerMsisdn(), CORRELATOR))
                .thenThrow(new EcocashApiTransientException("down", 503));

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.PENDING);
        verify(records, never()).markExpired(any(), anyString());
    }

    @Test
    @DisplayName("a non-ECOCASH-shaped row is never queried")
    void resolve_ignoresRowsWithoutCorrelator() {
        Payment p = openEcocashRow(null);
        p.setEcocashClientCorrelator(null);

        assertThat(service.resolveOpenCharge(p)).isEqualTo(EcocashCheckOutcome.PENDING);
        verify(client, never()).query(anyString(), anyString());
    }
}
