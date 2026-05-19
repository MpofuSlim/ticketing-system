package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.OradianMiddlewareClient;
import com.innbucks.loyaltyservice.client.OradianMiddlewareException;
import com.innbucks.loyaltyservice.client.OradianMiddlewareTransientException;
import com.innbucks.loyaltyservice.client.dto.CreditDepositAccountRequest;
import com.innbucks.loyaltyservice.client.dto.CreditDepositAccountResponse;
import com.innbucks.loyaltyservice.client.dto.DepositAccount;
import com.innbucks.loyaltyservice.client.dto.WithdrawDepositAccountRequest;
import com.innbucks.loyaltyservice.client.dto.WithdrawDepositAccountResponse;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.OradianSyncTransaction;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.OradianSyncTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OradianSyncServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SOURCE_TXN = UUID.randomUUID();
    private static final String PHONE = "254712345678";
    private static final String LPW_ID = "A8347323";

    private OradianMiddlewareClient client;
    private LoyaltyUserRepository userRepo;
    private OradianSyncTransactionRepository syncRepo;
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        client = mock(OradianMiddlewareClient.class);
        userRepo = mock(LoyaltyUserRepository.class);
        syncRepo = mock(OradianSyncTransactionRepository.class);
        // Trivial transaction manager — the REQUIRES_NEW template just
        // runs the callback. We don't need real transaction semantics
        // for these unit tests; the FAILED-row-write happens via the
        // mock syncRepo regardless.
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private OradianSyncService newService(boolean enabled) {
        return new OradianSyncService(
                enabled, "Cash", "Cash", "MobileBanking",
                client, userRepo, syncRepo, txManager);
    }

    private Wallet walletWith(String oradianAccountId) {
        Wallet w = new Wallet();
        w.setId(WALLET);
        w.setTenantId(TENANT);
        w.setUserId(USER);
        w.setBalance(BigDecimal.ZERO);
        w.setOradianAccountId(oradianAccountId);
        return w;
    }

    private LoyaltyUser userWith(LoyaltyUser.Status status) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(USER);
        u.setTenantId(TENANT);
        u.setPhoneNumber(PHONE);
        u.setStatus(status);
        return u;
    }

    private CreditDepositAccountResponse stubCredit() {
        return new CreditDepositAccountResponse(
                LPW_ID, "Cash", LocalDate.now(),
                "50.0000", "MobileBanking", "earn:PURCHASE",
                "EARN-77", "TX-9999", "CMD-7777");
    }

    private WithdrawDepositAccountResponse stubWithdraw() {
        return new WithdrawDepositAccountResponse(
                false, LPW_ID, "Cash", LocalDate.now(),
                "20.0000", "MobileBanking", "redeem:VOUCHER",
                "REDEEM-42", "TX-100", "CMD-101");
    }

    // ----- skip paths -----

    @Test
    void syncDelta_isNoOp_whenFeatureFlagDisabled() {
        OradianSyncService svc = newService(false);

        svc.syncDelta(walletWith(LPW_ID), new BigDecimal("50"), SOURCE_TXN, "earn:PURCHASE");

        verifyNoInteractions(client, userRepo, syncRepo);
    }

    @Test
    void syncDelta_isNoOp_whenDeltaIsZero() {
        // A no-op delta is a degenerate caller scenario (e.g. rule
        // evaluation produced zero points). We shouldn't even bother
        // looking up the user — let alone hitting Oradian.
        OradianSyncService svc = newService(true);

        svc.syncDelta(walletWith(LPW_ID), BigDecimal.ZERO, SOURCE_TXN, "earn:PURCHASE");

        verifyNoInteractions(client, userRepo, syncRepo);
    }

    @Test
    void syncDelta_skips_whenUserIsPENDING() {
        // PENDING users (pre-tier-2) don't have an Oradian customer
        // yet. Points accumulate locally and get backfilled on
        // promotion to ACTIVE.
        when(userRepo.findById(USER)).thenReturn(Optional.of(userWith(LoyaltyUser.Status.PENDING)));
        OradianSyncService svc = newService(true);

        svc.syncDelta(walletWith(LPW_ID), new BigDecimal("50"), SOURCE_TXN, "earn:PURCHASE");

        verifyNoInteractions(client, syncRepo);
    }

    @Test
    void syncDelta_skips_whenNoLpwAccountDiscovered() {
        // Customer has deposits but none with productID=LPW. Should
        // log a warning and skip — NOT crash the customer's earn.
        when(userRepo.findById(USER)).thenReturn(Optional.of(userWith(LoyaltyUser.Status.ACTIVE)));
        DepositAccount savings = new DepositAccount(
                null, "A100", null, null, "SAVINGS", "Savings",
                "0.00", "KES", "Active", "false", "", "", "",
                null, null, null, null);
        when(client.getDepositsForMsisdn(PHONE)).thenReturn(List.of(savings));

        OradianSyncService svc = newService(true);
        svc.syncDelta(walletWith(null), new BigDecimal("50"), SOURCE_TXN, "earn:PURCHASE");

        verify(client, never()).creditDepositAccount(any(), anyString());
        verify(syncRepo, never()).save(any());
    }

    // ----- success paths -----

    @Test
    void syncDelta_credit_callsOradianAndPersistsSucceededRow() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(userWith(LoyaltyUser.Status.ACTIVE)));
        when(client.creditDepositAccount(any(), anyString())).thenReturn(stubCredit());

        OradianSyncService svc = newService(true);
        Wallet w = walletWith(LPW_ID);
        svc.syncDelta(w, new BigDecimal("50.0000"), SOURCE_TXN, "earn:PURCHASE");

        // Verify request shape: positive delta → credit (not withdraw).
        ArgumentCaptor<CreditDepositAccountRequest> reqCap = ArgumentCaptor.forClass(CreditDepositAccountRequest.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(client).creditDepositAccount(reqCap.capture(), keyCap.capture());
        assertThat(reqCap.getValue().accountID()).isEqualTo(LPW_ID);
        assertThat(reqCap.getValue().amount()).isEqualTo("50.0000");
        assertThat(reqCap.getValue().paymentMethodName()).isEqualTo("Cash");
        // Idempotency key MUST be a parseable UUID — the row id we
        // also persist on OradianSyncTransaction.id. Without that
        // invariant, a network retry from us wouldn't dedup against
        // the same row.
        UUID parsedKey = UUID.fromString(keyCap.getValue());
        assertThat(parsedKey).isNotNull();

        // SUCCEEDED row persisted with all Oradian-assigned IDs.
        ArgumentCaptor<OradianSyncTransaction> rowCap = ArgumentCaptor.forClass(OradianSyncTransaction.class);
        verify(syncRepo).save(rowCap.capture());
        OradianSyncTransaction row = rowCap.getValue();
        assertThat(row.getStatus()).isEqualTo(OradianSyncTransaction.Status.SUCCEEDED);
        assertThat(row.getOradianAccountId()).isEqualTo(LPW_ID);
        assertThat(row.getOradianTransactionId()).isEqualTo("TX-9999");
        assertThat(row.getOradianCommandId()).isEqualTo("CMD-7777");
        assertThat(row.getOradianReferenceNumber()).isEqualTo("EARN-77");
        assertThat(row.getSourceTransactionId()).isEqualTo(SOURCE_TXN);
        assertThat(row.getDeltaPoints()).isEqualByComparingTo("50.0000");
        assertThat(row.getReason()).isEqualTo("earn:PURCHASE");
        assertThat(row.getCompletedAt()).isNotNull();
        // The row id must match the idempotency key we sent — same
        // UUID on both sides keeps the audit pivot trivial.
        assertThat(row.getId()).isEqualTo(parsedKey);
    }

    @Test
    void syncDelta_withdraw_callsOradianAndPersistsSucceededRow() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(userWith(LoyaltyUser.Status.ACTIVE)));
        when(client.withdrawFromDepositAccount(any(), anyString())).thenReturn(stubWithdraw());

        OradianSyncService svc = newService(true);
        svc.syncDelta(walletWith(LPW_ID), new BigDecimal("-20.0000"), SOURCE_TXN, "redeem:VOUCHER");

        // Negative delta → withdraw, with |delta| sent as positive amount
        // (Oradian's withdraw amount is unsigned — we never send "-20.00").
        ArgumentCaptor<WithdrawDepositAccountRequest> cap = ArgumentCaptor.forClass(WithdrawDepositAccountRequest.class);
        verify(client).withdrawFromDepositAccount(cap.capture(), anyString());
        assertThat(cap.getValue().amount()).isEqualTo("20.0000");
        // overrideLimitCheck MUST be false — customer-initiated
        // redemptions always respect Oradian's per-product limits.
        assertThat(cap.getValue().overrideLimitCheck()).isFalse();

        // Sync row preserves the signed delta (so the local PointsLedger
        // and the OradianSyncTransaction match on (wallet_id, delta)).
        ArgumentCaptor<OradianSyncTransaction> rowCap = ArgumentCaptor.forClass(OradianSyncTransaction.class);
        verify(syncRepo).save(rowCap.capture());
        assertThat(rowCap.getValue().getDeltaPoints()).isEqualByComparingTo("-20.0000");
        assertThat(rowCap.getValue().getStatus()).isEqualTo(OradianSyncTransaction.Status.SUCCEEDED);
    }

    @Test
    void syncDelta_discoversAndCachesLpwAccountId_onFirstSyncForWallet() {
        // wallet.oradianAccountId is null. The service should call
        // /internal/customers/{msisdn}/deposits, find the LPW row,
        // stash the ID onto the wallet, then proceed with the credit.
        when(userRepo.findById(USER)).thenReturn(Optional.of(userWith(LoyaltyUser.Status.ACTIVE)));
        DepositAccount savings = new DepositAccount(
                null, "A100", null, null, "SAVINGS", "Savings",
                "0.00", "KES", "Active", "false", "", "", "",
                null, null, null, null);
        DepositAccount lpw = new DepositAccount(
                null, LPW_ID, null, null, "LPW", "Loyalty Points Wallet",
                "0.00", "KES", "Active", "true", "", "", "",
                null, null, null, null);
        when(client.getDepositsForMsisdn(PHONE)).thenReturn(List.of(savings, lpw));
        when(client.creditDepositAccount(any(), anyString())).thenReturn(stubCredit());

        OradianSyncService svc = newService(true);
        Wallet w = walletWith(null);
        svc.syncDelta(w, new BigDecimal("50"), SOURCE_TXN, "earn:PURCHASE");

        // Wallet was mutated to cache the discovered LPW id — future
        // syncs skip the discovery call.
        assertThat(w.getOradianAccountId()).isEqualTo(LPW_ID);
        // And the credit call used the discovered id.
        ArgumentCaptor<CreditDepositAccountRequest> cap = ArgumentCaptor.forClass(CreditDepositAccountRequest.class);
        verify(client).creditDepositAccount(cap.capture(), anyString());
        assertThat(cap.getValue().accountID()).isEqualTo(LPW_ID);
    }

    // ----- failure paths -----

    @Test
    void syncDelta_oradianRejection_persistsFailedRowAndThrows() {
        // 4xx from Oradian → permanent (won't retry). Sync layer must
        // (a) persist a FAILED row in the REQUIRES_NEW template so the
        // audit trail survives the calling transaction's rollback,
        // (b) throw a LoyaltyException so the caller (WalletService)
        // rolls back the local mutation.
        when(userRepo.findById(USER)).thenReturn(Optional.of(userWith(LoyaltyUser.Status.ACTIVE)));
        when(client.creditDepositAccount(any(), anyString()))
                .thenThrow(new OradianMiddlewareException("Account not active", 422));

        OradianSyncService svc = newService(true);

        assertThatThrownBy(() ->
                svc.syncDelta(walletWith(LPW_ID), new BigDecimal("50"), SOURCE_TXN, "earn:PURCHASE"))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("Oradian sync failed")
                .hasMessageContaining("Account not active");

        // FAILED row persisted with UPSTREAM_REJECTED classification.
        ArgumentCaptor<OradianSyncTransaction> rowCap = ArgumentCaptor.forClass(OradianSyncTransaction.class);
        verify(syncRepo).save(rowCap.capture());
        OradianSyncTransaction row = rowCap.getValue();
        assertThat(row.getStatus()).isEqualTo(OradianSyncTransaction.Status.FAILED);
        assertThat(row.getFailureCode()).isEqualTo("UPSTREAM_REJECTED");
        assertThat(row.getFailureMessage()).contains("Account not active");
        assertThat(row.getCompletedAt()).isNotNull();
        // The REQUIRES_NEW template must have been used — proof:
        // getTransaction was called on the manager.
        verify(txManager).getTransaction(any());
    }

    @Test
    void syncDelta_oradianTransient_classifiesAsUpstreamUnavailable() {
        // 5xx / network / circuit-open → transient. Sync layer
        // classifies the FAILED row distinctly (UPSTREAM_UNAVAILABLE)
        // so the reconciliation + alerting paths can tell "Oradian was
        // down" from "Oradian said no".
        when(userRepo.findById(USER)).thenReturn(Optional.of(userWith(LoyaltyUser.Status.ACTIVE)));
        when(client.creditDepositAccount(any(), anyString()))
                .thenThrow(new OradianMiddlewareTransientException("read timeout", 502));

        OradianSyncService svc = newService(true);

        assertThatThrownBy(() ->
                svc.syncDelta(walletWith(LPW_ID), new BigDecimal("50"), SOURCE_TXN, "earn:PURCHASE"))
                .isInstanceOf(LoyaltyException.class);

        ArgumentCaptor<OradianSyncTransaction> rowCap = ArgumentCaptor.forClass(OradianSyncTransaction.class);
        verify(syncRepo).save(rowCap.capture());
        assertThat(rowCap.getValue().getFailureCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @Test
    void syncDelta_truncatesLongUpstreamErrorMessages_toFitFailureMessageColumn() {
        // failure_message column is VARCHAR(500). A pathological
        // upstream error message must be truncated server-side so the
        // FAILED row insert can't be refused by a database length
        // check.
        when(userRepo.findById(USER)).thenReturn(Optional.of(userWith(LoyaltyUser.Status.ACTIVE)));
        String huge = "x".repeat(2000);
        when(client.creditDepositAccount(any(), anyString()))
                .thenThrow(new OradianMiddlewareException(huge, 422));

        OradianSyncService svc = newService(true);
        assertThatThrownBy(() ->
                svc.syncDelta(walletWith(LPW_ID), new BigDecimal("50"), SOURCE_TXN, "earn:PURCHASE"))
                .isInstanceOf(LoyaltyException.class);

        ArgumentCaptor<OradianSyncTransaction> rowCap = ArgumentCaptor.forClass(OradianSyncTransaction.class);
        verify(syncRepo).save(rowCap.capture());
        assertThat(rowCap.getValue().getFailureMessage().length()).isEqualTo(500);
    }
}
