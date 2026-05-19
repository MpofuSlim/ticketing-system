package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.client.OradianMiddlewareClient;
import com.innbucks.loyaltyservice.client.OradianMiddlewareTransientException;
import com.innbucks.loyaltyservice.client.dto.DepositAccountSnapshot;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OradianBalanceAuditJobTest {

    private Wallet wallet(String localBalance, String oradianAccountId) {
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        w.setTenantId(UUID.randomUUID());
        w.setUserId(UUID.randomUUID());
        w.setBalance(new BigDecimal(localBalance));
        w.setOradianAccountId(oradianAccountId);
        return w;
    }

    private DepositAccountSnapshot snapshot(String balance) {
        return new DepositAccountSnapshot(
                "A8347323", balance, "Active", "LPW", "KES", "C001");
    }

    @Test
    void audit_doesNothing_whenFeatureFlagDisabled() {
        WalletRepository walletRepo = mock(WalletRepository.class);
        OradianMiddlewareClient client = mock(OradianMiddlewareClient.class);

        OradianBalanceAuditJob job = new OradianBalanceAuditJob(
                false, 200, new BigDecimal("0.0001"),
                walletRepo, client, new SimpleMeterRegistry());
        job.audit();

        verifyNoInteractions(walletRepo, client);
    }

    @Test
    void audit_skipsWalletsWhoseBalanceMatchesOradian_withinTolerance() {
        WalletRepository walletRepo = mock(WalletRepository.class);
        OradianMiddlewareClient client = mock(OradianMiddlewareClient.class);
        Wallet w = wallet("100.0000", "A8347323");
        when(walletRepo.findByOradianAccountIdIsNotNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(w)));
        when(client.getDepositAccount("A8347323")).thenReturn(snapshot("100.0000"));

        MeterRegistry registry = new SimpleMeterRegistry();
        OradianBalanceAuditJob job = new OradianBalanceAuditJob(
                true, 200, new BigDecimal("0.0001"),
                walletRepo, client, registry);
        job.audit();

        assertThat(registry.counter("loyalty.oradian_sync.balance_audit.checked").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("loyalty.oradian_sync.drift", "direction", "local_high").count())
                .isEqualTo(0.0);
        assertThat(registry.counter("loyalty.oradian_sync.drift", "direction", "local_low").count())
                .isEqualTo(0.0);
    }

    @Test
    void audit_incrementsLocalHigh_whenLocalBalanceExceedsOradianBeyondTolerance() {
        // Local says 100, Oradian says 90 — local OVER-credited
        // somehow. The dangerous direction (customer can redeem more
        // points than the brand owes). Operator must investigate.
        WalletRepository walletRepo = mock(WalletRepository.class);
        OradianMiddlewareClient client = mock(OradianMiddlewareClient.class);
        Wallet w = wallet("100.0000", "A8347323");
        when(walletRepo.findByOradianAccountIdIsNotNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(w)));
        when(client.getDepositAccount("A8347323")).thenReturn(snapshot("90.0000"));

        MeterRegistry registry = new SimpleMeterRegistry();
        OradianBalanceAuditJob job = new OradianBalanceAuditJob(
                true, 200, new BigDecimal("0.0001"),
                walletRepo, client, registry);
        job.audit();

        assertThat(registry.counter("loyalty.oradian_sync.drift", "direction", "local_high").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("loyalty.oradian_sync.drift", "direction", "local_low").count())
                .isEqualTo(0.0);
    }

    @Test
    void audit_incrementsLocalLow_whenOradianBalanceExceedsLocalBeyondTolerance() {
        // Local says 100, Oradian says 110 — customer EARNED on
        // Oradian but the local mutation didn't commit (e.g.
        // asymmetric rollback). Customer hasn't "lost" points in
        // the brand's books, but the local view under-reports —
        // they'll feel cheated when they redeem.
        WalletRepository walletRepo = mock(WalletRepository.class);
        OradianMiddlewareClient client = mock(OradianMiddlewareClient.class);
        Wallet w = wallet("100.0000", "A8347323");
        when(walletRepo.findByOradianAccountIdIsNotNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(w)));
        when(client.getDepositAccount("A8347323")).thenReturn(snapshot("110.0000"));

        MeterRegistry registry = new SimpleMeterRegistry();
        OradianBalanceAuditJob job = new OradianBalanceAuditJob(
                true, 200, new BigDecimal("0.0001"),
                walletRepo, client, registry);
        job.audit();

        assertThat(registry.counter("loyalty.oradian_sync.drift", "direction", "local_low").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("loyalty.oradian_sync.drift", "direction", "local_high").count())
                .isEqualTo(0.0);
    }

    @Test
    void audit_doesNotAutoCorrect_evenOnDrift() {
        // Critical invariant: drift detection is read-only. The job
        // never writes back to wallet.balance. An auto-correct here
        // would mask the bug + risk amplifying it.
        WalletRepository walletRepo = mock(WalletRepository.class);
        OradianMiddlewareClient client = mock(OradianMiddlewareClient.class);
        Wallet w = wallet("100.0000", "A8347323");
        when(walletRepo.findByOradianAccountIdIsNotNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(w)));
        when(client.getDepositAccount("A8347323")).thenReturn(snapshot("90.0000"));

        OradianBalanceAuditJob job = new OradianBalanceAuditJob(
                true, 200, new BigDecimal("0.0001"),
                walletRepo, client, new SimpleMeterRegistry());
        job.audit();

        verify(walletRepo, org.mockito.Mockito.never()).save(any());
        // Local balance is preserved.
        assertThat(w.getBalance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void audit_continuesAfterPerWalletLookupFailure() {
        // One bad wallet shouldn't abort the whole sweep. The
        // failure is logged + counted; the next wallet still gets
        // checked.
        WalletRepository walletRepo = mock(WalletRepository.class);
        OradianMiddlewareClient client = mock(OradianMiddlewareClient.class);
        Wallet broken = wallet("50.0000", "A_BROKEN");
        Wallet ok = wallet("100.0000", "A8347323");
        when(walletRepo.findByOradianAccountIdIsNotNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(broken, ok)));
        when(client.getDepositAccount("A_BROKEN"))
                .thenThrow(new OradianMiddlewareTransientException("read timeout", 502));
        when(client.getDepositAccount("A8347323")).thenReturn(snapshot("100.0000"));

        MeterRegistry registry = new SimpleMeterRegistry();
        OradianBalanceAuditJob job = new OradianBalanceAuditJob(
                true, 200, new BigDecimal("0.0001"),
                walletRepo, client, registry);
        job.audit();

        assertThat(registry.counter("loyalty.oradian_sync.balance_audit.lookup_failures").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("loyalty.oradian_sync.balance_audit.checked").count())
                .isEqualTo(2.0);
        // The OK wallet still got verified.
        verify(client).getDepositAccount("A8347323");
    }

    @Test
    void audit_handlesUnparseableOradianBalance_asLookupFailureNotCrash() {
        // Defensive: Oradian returns "abc" in the balance field (a
        // misconfiguration / format-change scenario). Must count as
        // a lookup failure, not throw an unhandled NumberFormatException
        // that aborts the entire sweep.
        WalletRepository walletRepo = mock(WalletRepository.class);
        OradianMiddlewareClient client = mock(OradianMiddlewareClient.class);
        Wallet w = wallet("100.0000", "A8347323");
        when(walletRepo.findByOradianAccountIdIsNotNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(w)));
        when(client.getDepositAccount("A8347323")).thenReturn(snapshot("not-a-number"));

        MeterRegistry registry = new SimpleMeterRegistry();
        OradianBalanceAuditJob job = new OradianBalanceAuditJob(
                true, 200, new BigDecimal("0.0001"),
                walletRepo, client, registry);
        job.audit();

        assertThat(registry.counter("loyalty.oradian_sync.balance_audit.lookup_failures").count())
                .isEqualTo(1.0);
        // Drift counters untouched — we can't compare against garbage.
        assertThat(registry.counter("loyalty.oradian_sync.drift", "direction", "local_high").count())
                .isEqualTo(0.0);
    }

    @Test
    void audit_walksAllPages() {
        WalletRepository walletRepo = mock(WalletRepository.class);
        OradianMiddlewareClient client = mock(OradianMiddlewareClient.class);
        Wallet w1 = wallet("100", "A1");
        Wallet w2 = wallet("100", "A2");
        Wallet w3 = wallet("100", "A3");
        // Two pages: [w1, w2] then [w3]
        Pageable p0 = org.springframework.data.domain.PageRequest.of(0, 2,
                org.springframework.data.domain.Sort.by("id").ascending());
        Pageable p1 = org.springframework.data.domain.PageRequest.of(1, 2,
                org.springframework.data.domain.Sort.by("id").ascending());
        when(walletRepo.findByOradianAccountIdIsNotNull(p0))
                .thenReturn(new PageImpl<>(List.of(w1, w2), p0, 3));
        when(walletRepo.findByOradianAccountIdIsNotNull(p1))
                .thenReturn(new PageImpl<>(List.of(w3), p1, 3));
        when(client.getDepositAccount(any())).thenReturn(snapshot("100"));

        MeterRegistry registry = new SimpleMeterRegistry();
        OradianBalanceAuditJob job = new OradianBalanceAuditJob(
                true, 2, new BigDecimal("0.0001"),
                walletRepo, client, registry);
        job.audit();

        assertThat(registry.counter("loyalty.oradian_sync.balance_audit.checked").count())
                .isEqualTo(3.0);
        verify(client).getDepositAccount("A1");
        verify(client).getDepositAccount("A2");
        verify(client).getDepositAccount("A3");
    }
}
