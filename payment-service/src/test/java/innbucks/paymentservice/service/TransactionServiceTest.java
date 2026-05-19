package innbucks.paymentservice.service;

import innbucks.paymentservice.client.OradianMiddlewareException;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.entity.TransactionStatus;
import innbucks.paymentservice.entity.TransactionType;
import innbucks.paymentservice.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        TransactionService svc = new TransactionService(repo);

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

        TransactionService svc = new TransactionService(repo);
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

        TransactionService svc = new TransactionService(repo);
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

        TransactionService svc = new TransactionService(repo);
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

        TransactionService svc = new TransactionService(repo);
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

        TransactionService svc = new TransactionService(repo);
        svc.markSucceeded(UUID.randomUUID(), "x", "y", null);

        verify(repo, never()).save(any());
    }
}
