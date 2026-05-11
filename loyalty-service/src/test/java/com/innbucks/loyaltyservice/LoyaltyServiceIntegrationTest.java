package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.QrToken;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.InvoicingService;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.QrService;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.TransferService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.VoucherTemplateService;
import com.innbucks.loyaltyservice.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class LoyaltyServiceIntegrationTest {

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired UserService userService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired TransferService transferService;
    @Autowired WalletService walletService;
    @Autowired VoucherTemplateService voucherTemplateService;
    @Autowired VoucherService voucherService;
    @Autowired QrService qrService;
    @Autowired InvoicingService invoicingService;

    // Stubbed so enrolment doesn't require a running user-service. Returns a
    // tier-1 customer for any phone number passed in.
    @MockitoBean UserServiceClient userServiceClient;

    @BeforeEach
    void stubUserServiceLookup() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(
                        new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));
    }

    @Test
    @Transactional
    void earnsPointsAndRedeemsVoucher() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Cafe Westgate", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new BigDecimal("0.001"),
                        new BigDecimal("0.05"),
                        new BigDecimal("0.10")));
        ruleAdminService.createRule(t.getId(), mr.id(),
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        LoyaltyUser u = userService.findOrEnrol(t.getId(), "+263770000001", mr.id());

        var txn = transactionService.post(t.getId(), mr.id(),
                new Dtos.TransactionRequest(null, u.getId(), TransactionType.PURCHASE,
                        new BigDecimal("100"), "USD", "ref-1"));
        assertThat(txn.pointsDelta()).isEqualByComparingTo("100");
        assertThat(txn.balanceAfter()).isEqualByComparingTo("100");

        // Issue + redeem voucher
        VoucherTemplate tpl = voucherTemplateService.create(t.getId(), mr.id(),
                new Dtos.VoucherTemplateRequest(null, "10% off",
                        VoucherTemplate.VoucherType.SINGLE_USE,
                        VoucherTemplate.ValueType.PERCENT,
                        new BigDecimal("10"), "USD", null, 1, 30, null));

        var v = voucherService.issue(t.getId(),
                new Dtos.IssueVoucherRequest(null, tpl.getId(), null, null, u.getId(),
                        Voucher.DeliveryChannel.NONE, null, null, null));

        var redemption = voucherService.redeem(t.getId(), mr.id(),
                new Dtos.RedeemVoucherRequest(null, v.code(), u.getId(),
                        "OUTLET-1", "device-A", "127.0.0.1"));
        assertThat(redemption.status()).isEqualTo(Voucher.Status.REDEEMED.name());

        // Duplicate redemption attempt is rejected
        assertThatThrownBy(() ->
                voucherService.redeem(t.getId(), mr.id(),
                        new Dtos.RedeemVoucherRequest(null, v.code(), u.getId(),
                                "OUTLET-1", "device-A", "127.0.0.1")))
                .isInstanceOf(LoyaltyException.class);
    }

    @Test
    @Transactional
    void transferDeductsAndCreditsAtomically() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Mall Bulawayo", "Retail", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        ruleAdminService.createRule(t.getId(), mr.id(),
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        LoyaltyUser alice = userService.findOrEnrol(t.getId(), "+263770000010", mr.id());
        LoyaltyUser bob = userService.findOrEnrol(t.getId(), "+263770000011", mr.id());

        transactionService.post(t.getId(), mr.id(),
                new Dtos.TransactionRequest(null, alice.getId(), TransactionType.PURCHASE,
                        new BigDecimal("50"), "USD", "ref-trans-1"));

        transferService.transfer(t.getId(),
                new Dtos.TransferRequest(alice.getId(), bob.getId(), new BigDecimal("20"), "gift"));

        assertThat(walletService.totalBalance(alice.getId())).isEqualByComparingTo("30");
        assertThat(walletService.totalBalance(bob.getId())).isEqualByComparingTo("20");
    }

    @Test
    @Transactional
    void qrConsumeAwardsPointsAndIsSingleUse() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Pump Mutare", "Fuel", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        ruleAdminService.createRule(t.getId(), mr.id(),
                new Dtos.RuleRequest(null, TransactionType.QR_PAY,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        LoyaltyUser u = userService.findOrEnrol(t.getId(), "+263770000020", mr.id());

        var qr = qrService.issue(t.getId(),
                new Dtos.QrIssueRequest(QrToken.SourceType.MERCHANT, mr.id(),
                        TransactionType.QR_PAY, new BigDecimal("25"), "USD", 60));

        var txn = qrService.consume(t.getId(),
                new Dtos.QrConsumeRequest(qr.token(), qr.signature(), u.getId(), "ref-qr-1"));
        assertThat(txn.pointsDelta()).isEqualByComparingTo("25");

        // Reuse must fail
        assertThatThrownBy(() -> qrService.consume(t.getId(),
                new Dtos.QrConsumeRequest(qr.token(), qr.signature(), u.getId(), "ref-qr-2")))
                .isInstanceOf(LoyaltyException.class);
    }

    @Test
    @Transactional
    void invoicingAggregatesFees() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Pharmacy Gweru", "Health", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new BigDecimal("0.01"),
                        new BigDecimal("1.00"),
                        new BigDecimal("0.50")));
        ruleAdminService.createRule(t.getId(), mr.id(),
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        LoyaltyUser u = userService.findOrEnrol(t.getId(), "+263770000030", mr.id());

        transactionService.post(t.getId(), mr.id(),
                new Dtos.TransactionRequest(null, u.getId(), TransactionType.PURCHASE,
                        new BigDecimal("100"), "USD", "ref-inv-1"));

        var merchant = merchantService.requireMerchant(t.getId(), mr.id());
        var inv = invoicingService.generate(merchant,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        // 100 points * 0.01 fee/point = 1.00
        assertThat(inv.getPointsIssued()).isEqualByComparingTo("100");
        assertThat(inv.getTotalAmount()).isEqualByComparingTo("1.00");
    }

    private Tenant saveTenant() {
        Tenant t = new Tenant();
        t.setCode("ACME-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Acme");
        t.setCreatedAt(Instant.now());
        return tenantRepository.save(t);
    }
}
