package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.EarnRequestDTO;
import com.innbucks.loyaltyservice.dto.LoyaltyRuleDTO;
import com.innbucks.loyaltyservice.dto.LoyaltyTransactionDTO;
import com.innbucks.loyaltyservice.dto.RedeemRequestDTO;
import com.innbucks.loyaltyservice.entity.LoyaltyAccount;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.exception.InsufficientPointsException;
import com.innbucks.loyaltyservice.repository.LoyaltyAccountRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LoyaltyServiceTest {

    private LoyaltyRuleRepository ruleRepo;
    private LoyaltyAccountRepository accountRepo;
    private LoyaltyTransactionRepository txRepo;
    private LoyaltyService service;

    private final Map<String, LoyaltyRule> ruleStore = new HashMap<>();
    private final Map<Long, LoyaltyAccount> accountStore = new HashMap<>();
    private final AtomicLong accountIds = new AtomicLong(1);
    private final AtomicLong txIds = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        ruleRepo = mock(LoyaltyRuleRepository.class);
        accountRepo = mock(LoyaltyAccountRepository.class);
        txRepo = mock(LoyaltyTransactionRepository.class);
        ruleStore.clear();
        accountStore.clear();

        when(ruleRepo.findByTenantId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(ruleStore.get(inv.getArgument(0, String.class))));
        when(ruleRepo.save(any(LoyaltyRule.class))).thenAnswer(inv -> {
            LoyaltyRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId(99L);
            ruleStore.put(r.getTenantId(), r);
            return r;
        });

        when(accountRepo.findByCustomerIdAndTenantId(anyString(), anyString())).thenAnswer(inv ->
                accountStore.values().stream()
                        .filter(a -> a.getCustomerId().equals(inv.getArgument(0))
                                && a.getTenantId().equals(inv.getArgument(1)))
                        .findFirst());
        when(accountRepo.save(any(LoyaltyAccount.class))).thenAnswer(inv -> {
            LoyaltyAccount a = inv.getArgument(0);
            if (a.getId() == null) a.setId(accountIds.getAndIncrement());
            accountStore.put(a.getId(), a);
            return a;
        });

        when(txRepo.findByAccountIdAndTypeAndReference(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(txRepo.save(any(LoyaltyTransaction.class))).thenAnswer(inv -> {
            LoyaltyTransaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(txIds.getAndIncrement());
            return t;
        });

        service = new LoyaltyService(ruleRepo, accountRepo, txRepo);
        ReflectionTestUtils.setField(service, "defaultEarnRate", new BigDecimal("1.0"));
        ReflectionTestUtils.setField(service, "defaultRedeemRate", new BigDecimal("10.0"));
    }

    @Test
    void getOrDefaultRule_returnsDefault_whenTenantHasNoRule() {
        LoyaltyRule rule = service.getOrDefaultRule("tenant-A");
        assertEquals(new BigDecimal("1.0"), rule.getEarnRate());
        assertEquals(new BigDecimal("10.0"), rule.getRedeemRate());
        assertTrue(rule.isActive());
        assertNull(rule.getId(), "default rule must be in-memory only");
    }

    @Test
    void upsertRule_persistsConfiguration() {
        service.upsertRule("tenant-A", LoyaltyRuleDTO.builder()
                .earnRate(new BigDecimal("2.0"))
                .redeemRate(new BigDecimal("5.0"))
                .active(true)
                .build());

        LoyaltyRule fetched = service.getOrDefaultRule("tenant-A");
        assertEquals(new BigDecimal("2.0"), fetched.getEarnRate());
        assertEquals(new BigDecimal("5.0"), fetched.getRedeemRate());
    }

    @Test
    void earn_creditsPointsBasedOnRule() {
        ruleStore.put("tenant-A", LoyaltyRule.builder()
                .tenantId("tenant-A")
                .earnRate(new BigDecimal("2.0"))
                .redeemRate(new BigDecimal("10.0"))
                .active(true)
                .id(1L)
                .build());

        LoyaltyTransactionDTO tx = service.earn(EarnRequestDTO.builder()
                .customerId("alice@example.com")
                .tenantId("tenant-A")
                .cashAmount(new BigDecimal("10.00"))
                .reference("booking-1")
                .build());

        assertEquals(0, new BigDecimal("20.0000").compareTo(tx.getPoints()));
        assertEquals(LoyaltyTransaction.Type.EARN, tx.getType());
        assertEquals(0, new BigDecimal("20.0000").compareTo(tx.getBalanceAfter()));
    }

    @Test
    void earn_isIdempotentByReference() {
        // First earn returns a transaction
        LoyaltyTransactionDTO first = service.earn(EarnRequestDTO.builder()
                .customerId("alice").tenantId("tenant-A")
                .cashAmount(new BigDecimal("5.00")).reference("ref-x")
                .build());

        // Set up the mock so the second lookup hits idempotent branch
        LoyaltyTransaction stored = LoyaltyTransaction.builder()
                .id(first.getId())
                .accountId(1L)
                .type(LoyaltyTransaction.Type.EARN)
                .points(first.getPoints())
                .reference("ref-x")
                .build();
        when(txRepo.findByAccountIdAndTypeAndReference(1L, LoyaltyTransaction.Type.EARN, "ref-x"))
                .thenReturn(Optional.of(stored));

        LoyaltyTransactionDTO second = service.earn(EarnRequestDTO.builder()
                .customerId("alice").tenantId("tenant-A")
                .cashAmount(new BigDecimal("5.00")).reference("ref-x")
                .build());

        assertEquals(first.getId(), second.getId());
        // Still only one save (the original)
        verify(txRepo, times(1)).save(any(LoyaltyTransaction.class));
    }

    @Test
    void redeem_debitsPointsFromBalance() {
        // Seed an account with 50 points
        accountStore.put(7L, LoyaltyAccount.builder()
                .id(7L)
                .customerId("alice").tenantId("tenant-A")
                .balance(new BigDecimal("50.0000"))
                .build());

        LoyaltyTransactionDTO tx = service.redeem(RedeemRequestDTO.builder()
                .customerId("alice").tenantId("tenant-A")
                .points(new BigDecimal("20")).reference("booking-r1")
                .build());

        assertEquals(LoyaltyTransaction.Type.REDEEM, tx.getType());
        assertEquals(0, new BigDecimal("30.0000").compareTo(tx.getBalanceAfter()));
    }

    @Test
    void redeem_failsWhenBalanceTooLow() {
        accountStore.put(8L, LoyaltyAccount.builder()
                .id(8L)
                .customerId("bob").tenantId("tenant-A")
                .balance(new BigDecimal("5.0000"))
                .build());

        InsufficientPointsException ex = assertThrows(InsufficientPointsException.class,
                () -> service.redeem(RedeemRequestDTO.builder()
                        .customerId("bob").tenantId("tenant-A")
                        .points(new BigDecimal("20")).reference("ref-fail")
                        .build()));
        assertTrue(ex.getMessage().contains("Insufficient"));
    }

    @Test
    void getBalance_zeroForNewCustomer() {
        var balance = service.getBalance("ghost@example.com", "tenant-A");
        assertEquals(BigDecimal.ZERO, balance.getBalance());
    }
}
