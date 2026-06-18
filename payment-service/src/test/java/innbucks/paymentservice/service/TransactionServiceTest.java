package innbucks.paymentservice.service;

import innbucks.paymentservice.client.OradianMiddlewareException;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.entity.TransactionStatus;
import innbucks.paymentservice.entity.TransactionType;
import innbucks.paymentservice.messaging.TransactionCompletedEvent;
import innbucks.paymentservice.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionServiceTest {

    private static Transaction draftDeposit() {
        return Transaction.builder()
                .transactionType(TransactionType.TRANSFER)
                .customerPhone("+254777224008")
                .sourceAccountId("A000001")
                .destinationAccountId("A000002")
                .amount(new BigDecimal("123.00"))
                .transactionDate(LocalDate.of(2026, 5, 18))
                .build();
    }

    @Test
    void openPending_savesRowWithPendingStatus_andReturnsPersistedEntity() {
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        TransactionService svc = new TransactionService(repo, mock(ApplicationEventPublisher.class), mock(TransferLimitService.class));

        Transaction result = svc.openPending(draftDeposit());

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(repo).save(saved.capture());
        assertEquals(TransactionStatus.PENDING, saved.getValue().getStatus(),
                "openPending must persist the row in PENDING state");
        assertNotNull(result.getId());
    }

    @Test
    void markSucceeded_flipsStatusToSucceeded_andStampsUpstreamIds() {
        TransactionRepository repo = mock(TransactionRepository.class);
        UUID id = UUID.randomUUID();
        Transaction existing = draftDeposit();
        existing.setId(id);
        existing.setStatus(TransactionStatus.PENDING);
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        TransactionService svc = new TransactionService(repo, mock(ApplicationEventPublisher.class), mock(TransferLimitService.class));
        svc.markSucceeded(id, "oradian-9999", "ref-1234", "cmd-77");

        assertEquals(TransactionStatus.SUCCEEDED, existing.getStatus());
        assertEquals("oradian-9999", existing.getOradianTransactionId());
        assertEquals("ref-1234", existing.getOradianReferenceNumber());
        assertEquals("cmd-77", existing.getOradianCommandId());
        assertNotNull(existing.getCompletedAt(), "completedAt must be stamped on success");
        verify(repo).save(existing);
    }

    @Test
    void markFailed_flipsStatusToFailed_andClassifiesUpstreamByStatusCode() {
        TransactionRepository repo = mock(TransactionRepository.class);
        UUID id = UUID.randomUUID();
        Transaction existing = draftDeposit();
        existing.setId(id);
        existing.setStatus(TransactionStatus.PENDING);
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        TransactionService svc = new TransactionService(repo, mock(ApplicationEventPublisher.class), mock(TransferLimitService.class));
        svc.markFailed(id, new OradianMiddlewareException("Insufficient funds", 422));

        assertEquals(TransactionStatus.FAILED, existing.getStatus());
        assertEquals("UPSTREAM_REJECTED", existing.getFailureCode(),
                "422 is a 4xx => UPSTREAM_REJECTED");
        assertEquals("Insufficient funds", existing.getFailureMessage());
        assertNotNull(existing.getCompletedAt());
        verify(repo).save(existing);
    }

    @Test
    void markFailed_classifies502As_UPSTREAM_UNAVAILABLE() {
        TransactionRepository repo = mock(TransactionRepository.class);
        UUID id = UUID.randomUUID();
        Transaction existing = draftDeposit();
        existing.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        TransactionService svc = new TransactionService(repo, mock(ApplicationEventPublisher.class), mock(TransferLimitService.class));
        svc.markFailed(id, new OradianMiddlewareException("connect timed out", 502));

        assertEquals("UPSTREAM_UNAVAILABLE", existing.getFailureCode());
    }

    @Test
    void markFailed_truncatesLongMessagesToFitFailureMessageColumn() {
        TransactionRepository repo = mock(TransactionRepository.class);
        UUID id = UUID.randomUUID();
        Transaction existing = draftDeposit();
        existing.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        TransactionService svc = new TransactionService(repo, mock(ApplicationEventPublisher.class), mock(TransferLimitService.class));
        String huge = "x".repeat(2000);
        svc.markFailed(id, new OradianMiddlewareException(huge, 422));

        assertEquals(500, existing.getFailureMessage().length(),
                "failure_message column is VARCHAR(500); messages MUST be truncated to fit");
    }

    @Test
    void markSucceeded_isNoOp_whenRowDoesNotExist() {
        // Defensive: caller just inserted, so this branch shouldn't fire in
        // practice, but a silent no-op is safer than a NPE.
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findById(any(UUID.class))).thenReturn(Optional.empty());

        TransactionService svc = new TransactionService(repo, mock(ApplicationEventPublisher.class), mock(TransferLimitService.class));
        svc.markSucceeded(UUID.randomUUID(), "x", "y", null);

        verify(repo, never()).save(any());
    }

    @Test
    void markSucceeded_publishesTransactionCompletedEvent_withSucceededStatus() {
        TransactionRepository repo = mock(TransactionRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        UUID id = UUID.randomUUID();
        Transaction existing = draftDeposit();
        existing.setId(id);
        existing.setStatus(TransactionStatus.PENDING);
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        new TransactionService(repo, publisher, mock(TransferLimitService.class))
                .markSucceeded(id, "oradian-9999", "ref-1234", "cmd-77");

        ArgumentCaptor<TransactionCompletedEvent> captor =
                ArgumentCaptor.forClass(TransactionCompletedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        TransactionCompletedEvent event = captor.getValue();
        assertEquals(id, event.transactionId());
        assertEquals("SUCCEEDED", event.status());
        assertEquals("oradian-9999", event.oradianTransactionId(),
                "the event must carry the Oradian-assigned IDs so downstream consumers " +
                        "(notification service, statement generator) can render receipts " +
                        "without a second call");
        assertEquals("ref-1234", event.oradianReferenceNumber());
        assertEquals("cmd-77", event.oradianCommandId());
        assertNotNull(event.eventId(), "every event carries its own UUID for downstream dedup");
        assertNotNull(event.eventTime());
    }

    @Test
    void markFailed_publishesTransactionCompletedEvent_withFailedStatus_andClassification() {
        TransactionRepository repo = mock(TransactionRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        UUID id = UUID.randomUUID();
        Transaction existing = draftDeposit();
        existing.setId(id);
        existing.setStatus(TransactionStatus.PENDING);
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        new TransactionService(repo, publisher, mock(TransferLimitService.class))
                .markFailed(id, new OradianMiddlewareException("Insufficient funds", 422));

        ArgumentCaptor<TransactionCompletedEvent> captor =
                ArgumentCaptor.forClass(TransactionCompletedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        TransactionCompletedEvent event = captor.getValue();
        assertEquals("FAILED", event.status(),
                "failed transactions still emit an event — downstream needs them to " +
                        "render 'your transfer was rejected, here's why' UX");
        assertEquals("UPSTREAM_REJECTED", event.failureCode());
        assertEquals("Insufficient funds", event.failureMessage());
    }

    @Test
    void openPending_doesNotPublishEvent_pendingIsNotTerminal() {
        // Only terminal states (SUCCEEDED / FAILED) emit events. PENDING is
        // a transient lifecycle marker; publishing on insert would double
        // every downstream notification (once on pending, once on terminal).
        TransactionRepository repo = mock(TransactionRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(repo.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });

        new TransactionService(repo, publisher, mock(TransferLimitService.class)).openPending(draftDeposit());

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void markSucceeded_doesNotPublishEvent_whenRowMissing() {
        // Defensive: the row should exist (caller just inserted it), but a
        // logic gap shouldn't emit an event saying "transaction X succeeded"
        // for a transaction X that isn't in the ledger.
        TransactionRepository repo = mock(TransactionRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(repo.findById(any(UUID.class))).thenReturn(Optional.empty());

        new TransactionService(repo, publisher, mock(TransferLimitService.class))
                .markSucceeded(UUID.randomUUID(), "x", "y", null);

        verify(publisher, never()).publishEvent(any());
    }
}
