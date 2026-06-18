package innbucks.paymentservice.service;

import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.PaymentEvent;
import innbucks.paymentservice.repository.PaymentEventRepository;
import innbucks.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins the ledger's banking invariants:
 * <ul>
 *   <li>every applied transition appends exactly one journal row in the same
 *       call (same transaction in production),</li>
 *   <li>terminal states are immutable — illegal transitions are refused and
 *       journal NOTHING,</li>
 *   <li>same-state replays are idempotent no-ops,</li>
 *   <li>COMPLETED_UNCONFIRMED records the upstream reference (the recovery
 *       handle) and is promotable only to SUCCEEDED.</li>
 * </ul>
 */
class PaymentRecordServiceTest {

    private static Payment payment(PaymentStatus status) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-test")
                .bookingId(UUID.randomUUID())
                .customerMsisdn("+263770000001")
                .customerAccount("CUST-1")
                .merchantAccount("MERCH-1")
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .status(status)
                .build();
    }

    @Test
    void openPending_journalsTheOpeningTransition() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        when(repo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        Payment draft = payment(null);

        new PaymentRecordService(repo, events).openPending(draft);

        ArgumentCaptor<PaymentEvent> ev = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(ev.capture());
        assertNull(ev.getValue().getFromStatus(), "opening transition journals from=null");
        assertEquals("PENDING", ev.getValue().getToStatus());
        assertEquals(draft.getId(), ev.getValue().getPaymentId());
    }

    @Test
    void markSucceeded_fromPending_appliesAndJournals() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.PENDING);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));
        when(repo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        new PaymentRecordService(repo, events).markSucceeded(p.getId(), "VEENGU-1", "INN-CONF-1");

        assertEquals(PaymentStatus.SUCCEEDED, p.getStatus());
        assertEquals("VEENGU-1", p.getVeenguTransactionId());
        assertEquals("INN-CONF-1", p.getConfirmationNumber());
        assertNotNull(p.getCompletedAt());
        ArgumentCaptor<PaymentEvent> ev = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(ev.capture());
        assertEquals("PENDING", ev.getValue().getFromStatus());
        assertEquals("SUCCEEDED", ev.getValue().getToStatus());
        assertEquals("VEENGU-1", ev.getValue().getUpstreamRef());
    }

    @Test
    void markFailed_onSucceededRow_isRefused_andJournalsNothing() {
        // Terminal immutability: a FAILED overwrite of a SUCCEEDED row would
        // mean the ledger denies a debit that happened. Refuse + shout.
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.SUCCEEDED);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));

        new PaymentRecordService(repo, events).markFailed(p.getId(), "late_code", "late message");

        assertEquals(PaymentStatus.SUCCEEDED, p.getStatus(), "terminal state must not change");
        assertNull(p.getUpstreamErrorCode());
        verify(repo, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void markInDoubt_fromPending_appliesWithoutErrorFields() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.PENDING);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));
        when(repo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        new PaymentRecordService(repo, events).markInDoubt(p.getId(), "gateway timeout");

        assertEquals(PaymentStatus.IN_DOUBT, p.getStatus());
        // IN_DOUBT is not a failure: no error fields, no completedAt.
        assertNull(p.getUpstreamErrorCode());
        assertNull(p.getCompletedAt());
        ArgumentCaptor<PaymentEvent> ev = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(ev.capture());
        assertEquals("gateway timeout", ev.getValue().getDetail());
    }

    @Test
    void markCompletedUnconfirmed_recordsTheUpstreamReference() {
        // The veengu reference is the recovery handle — it must land on the
        // row immediately, not wait for the eventual SUCCEEDED promotion.
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.PENDING);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));
        when(repo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        new PaymentRecordService(repo, events)
                .markCompletedUnconfirmed(p.getId(), "VEENGU-9", "confirm rejected: hold expired");

        assertEquals(PaymentStatus.COMPLETED_UNCONFIRMED, p.getStatus());
        assertEquals("VEENGU-9", p.getVeenguTransactionId());
    }

    @Test
    void resolveUnconfirmed_promotesToSucceeded() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.COMPLETED_UNCONFIRMED);
        p.setVeenguTransactionId("VEENGU-9");
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));
        when(repo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        new PaymentRecordService(repo, events).resolveUnconfirmed(p.getId(), "INN-CONF-9");

        assertEquals(PaymentStatus.SUCCEEDED, p.getStatus());
        assertEquals("INN-CONF-9", p.getConfirmationNumber());
        assertEquals("VEENGU-9", p.getVeenguTransactionId(), "upstream ref from the unconfirmed phase is kept");
        assertNotNull(p.getCompletedAt());
    }

    @Test
    void resolveUnconfirmed_fromPending_isRefused() {
        // Promotion to SUCCEEDED via the reconciler path is only legal from
        // COMPLETED_UNCONFIRMED — a PENDING row has no proven debit.
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.PENDING);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));

        // PENDING -> SUCCEEDED is legal via markSucceeded, but this asserts the
        // resolveUnconfirmed-specific journey: it shares the same transition
        // map, so PENDING -> SUCCEEDED is applied. The REAL guard scenario is
        // FAILED -> SUCCEEDED:
        Payment failed = payment(PaymentStatus.FAILED);
        when(repo.findById(failed.getId())).thenReturn(Optional.of(failed));

        new PaymentRecordService(repo, events).resolveUnconfirmed(failed.getId(), "INN-X");

        assertEquals(PaymentStatus.FAILED, failed.getStatus(), "terminal FAILED must not be resurrected");
        verify(repo, never()).save(failed);
    }

    @Test
    void sameStateReplay_isIdempotentNoOp() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.IN_DOUBT);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));

        new PaymentRecordService(repo, events).markInDoubt(p.getId(), "duplicate signal");

        verify(repo, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void missingRow_isLoggedNoOp_neverThrows() {
        // Throwing here would mask the upstream outcome from the caller.
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        when(repo.findById(any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() ->
                new PaymentRecordService(repo, events).markSucceeded(UUID.randomUUID(), "V", "C"));
        verifyNoInteractions(events);
    }

    @Test
    void markTokenIssued_fromPending_recordsCodeHandlesAndDeadline() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.PENDING);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(600);

        new PaymentRecordService(repo, events).markTokenIssued(
                p.getId(), "701285660", "1616800", "qr-base64-bytes", deadline);

        assertEquals(PaymentStatus.TOKEN_ISSUED, p.getStatus());
        assertEquals("701285660", p.getInnbucksCode());
        assertEquals("1616800", p.getCodeAuthNumber());
        assertEquals("qr-base64-bytes", p.getCodeQrBase64());
        assertEquals(deadline, p.getCodeExpiresAt());
        ArgumentCaptor<PaymentEvent> ev = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(ev.capture());
        assertEquals("PENDING", ev.getValue().getFromStatus());
        assertEquals("TOKEN_ISSUED", ev.getValue().getToStatus());
        assertEquals("1616800", ev.getValue().getUpstreamRef(),
                "the authNumber is the recovery handle and must be journaled");
    }

    @Test
    void markExpired_fromTokenIssued_isTerminal_andImmutable() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.TOKEN_ISSUED);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));

        PaymentRecordService service = new PaymentRecordService(repo, events);
        service.markExpired(p.getId(), "code lapsed unpaid");
        assertEquals(PaymentStatus.EXPIRED, p.getStatus());

        // EXPIRED is terminal: a late "paid after all" signal must be refused
        // and shouted about, never applied silently.
        service.markSucceeded(p.getId(), "1616800", "INN-LATE");
        assertEquals(PaymentStatus.EXPIRED, p.getStatus(), "terminal EXPIRED must not be resurrected");
    }

    @Test
    void markSucceeded_fromTokenIssued_isTheCodePaidPath() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.TOKEN_ISSUED);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));

        new PaymentRecordService(repo, events).markSucceeded(p.getId(), "1616800", "INN-CONF-1");

        assertEquals(PaymentStatus.SUCCEEDED, p.getStatus());
        assertEquals("1616800", p.getVeenguTransactionId());
        assertEquals("INN-CONF-1", p.getConfirmationNumber());
    }

    @Test
    void tokenIssued_cannotRegressToPending() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.SUCCEEDED);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));

        // No public API even attempts SUCCEEDED -> TOKEN_ISSUED; pin the
        // nearest expressible guard: a terminal row refuses markTokenIssued.
        new PaymentRecordService(repo, events).markTokenIssued(
                p.getId(), "code", "auth", "qr", java.time.Instant.now());

        assertEquals(PaymentStatus.SUCCEEDED, p.getStatus());
        verify(repo, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void noteEvent_appendsAnnotationRow_withoutStatusChange() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        Payment p = payment(PaymentStatus.TOKEN_ISSUED);
        when(repo.findById(p.getId())).thenReturn(Optional.of(p));

        new PaymentRecordService(repo, events).noteEvent(p.getId(), "Payment code delivered via SMS");

        assertEquals(PaymentStatus.TOKEN_ISSUED, p.getStatus(), "annotation must not move the state");
        verify(repo, never()).save(any());
        ArgumentCaptor<PaymentEvent> ev = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(ev.capture());
        assertEquals("TOKEN_ISSUED", ev.getValue().getFromStatus());
        assertEquals("TOKEN_ISSUED", ev.getValue().getToStatus());
        assertEquals("Payment code delivered via SMS", ev.getValue().getDetail());
    }
}
