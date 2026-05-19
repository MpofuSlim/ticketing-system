package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.entity.OradianSyncTransaction;
import com.innbucks.loyaltyservice.repository.OradianSyncTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OradianSyncReconciliationJobTest {

    private OradianSyncTransaction stalePending(UUID id) {
        OradianSyncTransaction tx = new OradianSyncTransaction();
        tx.setId(id);
        tx.setTenantId(UUID.randomUUID());
        tx.setWalletId(UUID.randomUUID());
        tx.setDeltaPoints(new BigDecimal("50.0000"));
        tx.setReason("earn:PURCHASE");
        tx.setStatus(OradianSyncTransaction.Status.PENDING);
        tx.setOradianAccountId("A8347323");
        tx.setCreatedAt(Instant.now().minus(Duration.ofMinutes(10)));
        return tx;
    }

    @Test
    void reconcile_doesNothing_whenFeatureFlagDisabled() {
        OradianSyncTransactionRepository repo = mock(OradianSyncTransactionRepository.class);
        OradianSyncReconciliationJob job = new OradianSyncReconciliationJob(
                false, Duration.ofMinutes(2), repo, new SimpleMeterRegistry());

        job.reconcile();

        verifyNoInteractions(repo);
    }

    @Test
    void reconcile_marksStalePendingAsFailed_withRECONCILED_UNKNOWN_OUTCOME() {
        // Conservative policy: we don't re-issue the original credit
        // call to find out what happened on Oradian. The
        // balance-audit job is the authoritative drift detector;
        // here we just close the row out so it doesn't sit PENDING
        // forever blocking dashboards.
        OradianSyncTransactionRepository repo = mock(OradianSyncTransactionRepository.class);
        OradianSyncTransaction stale = stalePending(UUID.randomUUID());
        when(repo.findStalePending(any(Instant.class))).thenReturn(List.of(stale));

        OradianSyncReconciliationJob job = new OradianSyncReconciliationJob(
                true, Duration.ofMinutes(2), repo, new SimpleMeterRegistry());
        job.reconcile();

        assertThat(stale.getStatus()).isEqualTo(OradianSyncTransaction.Status.FAILED);
        assertThat(stale.getFailureCode()).isEqualTo("RECONCILED_UNKNOWN_OUTCOME");
        assertThat(stale.getFailureMessage()).contains("grace window");
        assertThat(stale.getCompletedAt()).isNotNull();
    }

    @Test
    void reconcile_queriesWithCutoffFromGraceWindow() {
        OradianSyncTransactionRepository repo = mock(OradianSyncTransactionRepository.class);
        when(repo.findStalePending(any(Instant.class))).thenReturn(List.of());

        OradianSyncReconciliationJob job = new OradianSyncReconciliationJob(
                true, Duration.ofMinutes(5), repo, new SimpleMeterRegistry());
        Instant before = Instant.now();
        job.reconcile();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repo).findStalePending(cutoffCaptor.capture());
        // Cutoff must be roughly now - 5min. Allow a generous range
        // to absorb test-execution time.
        Instant cutoff = cutoffCaptor.getValue();
        assertThat(cutoff).isBetween(
                before.minus(Duration.ofMinutes(5)).minus(Duration.ofSeconds(2)),
                after.minus(Duration.ofMinutes(5)).plus(Duration.ofSeconds(2)));
    }

    @Test
    void reconcile_doesNotSaveExplicitly_relyingOnDirtyChecking() {
        // The job is @Transactional and the entities are managed —
        // setStatus / setFailureCode get persisted by Hibernate's
        // dirty checking on commit. We don't call repo.save()
        // explicitly. Pinning this so a future refactor that adds a
        // save() doesn't accidentally do a no-op INSERT.
        OradianSyncTransactionRepository repo = mock(OradianSyncTransactionRepository.class);
        OradianSyncTransaction stale = stalePending(UUID.randomUUID());
        when(repo.findStalePending(any(Instant.class))).thenReturn(List.of(stale));

        OradianSyncReconciliationJob job = new OradianSyncReconciliationJob(
                true, Duration.ofMinutes(2), repo, new SimpleMeterRegistry());
        job.reconcile();

        verify(repo, never()).save(any());
    }
}
