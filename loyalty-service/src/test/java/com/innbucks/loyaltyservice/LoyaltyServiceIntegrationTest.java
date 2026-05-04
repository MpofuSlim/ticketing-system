package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.dto.Dtos;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @Transactional
    void earnsPointsAndRedeemsVoucher() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Cafe", "F&B", "Harare", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new BigDecimal("0.001"),
                        new BigDecimal("0.05"),
                        new BigDecimal("0.10")));
        ruleAdminService.createRule(t.getId(),
                new Dtos.RuleRequest(mr.id(), TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        Dtos.UserResponse u = userService.create(t.getId(),
                new Dtos.UserRequest("+263770000001", null, "Alice", null, "ZW", mr.id(), null));

        var txn = transactionService.post(t.getId(),
                new Dtos.TransactionRequest(mr.id(), u.id(), TransactionType.PURCHASE,
                        new BigDecimal("100"), "USD", "ref-1"));
        assertThat(txn.pointsDelta()).isEqualByComparingTo("100");
        assertThat(txn.balanceAfter()).isEqualByComparingTo("100");

        // Issue + redeem voucher
        VoucherTemplate tpl = voucherTemplateService.create(t.getId(),
                new Dtos.VoucherTemplateRequest(mr.id(), "10% off",
                        VoucherTemplate.VoucherType.SINGLE_USE,
                        VoucherTemplate.ValueType.PERCENT,
                        new BigDecimal("10"), "USD", null, 1, 30, null));

        var v = voucherService.issue(t.getId(),
                new Dtos.IssueVoucherRequest(tpl.getId(), null, null, u.id(),
                        Voucher.DeliveryChannel.NONE, null, null, null));

        var redemption = voucherService.redeem(t.getId(),
                new Dtos.RedeemVoucherRequest(v.code(), u.id(), mr.id(),
                        "OUTLET-1", "device-A", "127.0.0.1"));
        assertThat(redemption.status()).isEqualTo(Voucher.Status.REDEEMED.name());

        // Duplicate redemption attempt is rejected
        assertThatThrownBy(() ->
                voucherService.redeem(t.getId(),
                        new Dtos.RedeemVoucherRequest(v.code(), u.id(), mr.id(),
                                "OUTLET-1", "device-A", "127.0.0.1")))
                .isInstanceOf(LoyaltyException.class);
    }

    @Test
    @Transactional
    void transferDeductsAndCreditsAtomically() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Mall", "Retail", "Bulawayo", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        ruleAdminService.createRule(t.getId(),
                new Dtos.RuleRequest(mr.id(), TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        Dtos.UserResponse alice = userService.create(t.getId(),
                new Dtos.UserRequest("+263770000010", null, "Alice", null, "ZW", mr.id(), null));
        Dtos.UserResponse bob = userService.create(t.getId(),
                new Dtos.UserRequest("+263770000011", null, "Bob", null, "ZW", mr.id(), null));

        transactionService.post(t.getId(),
                new Dtos.TransactionRequest(mr.id(), alice.id(), TransactionType.PURCHASE,
                        new BigDecimal("50"), "USD", "ref-trans-1"));

        transferService.transfer(t.getId(),
                new Dtos.TransferRequest(alice.id(), bob.id(), new BigDecimal("20"), "gift"));

        assertThat(walletService.totalBalance(alice.id())).isEqualByComparingTo("30");
        assertThat(walletService.totalBalance(bob.id())).isEqualByComparingTo("20");
    }

    @Test
    @Transactional
    void qrConsumeAwardsPointsAndIsSingleUse() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Pump", "Fuel", "Mutare", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        ruleAdminService.createRule(t.getId(),
                new Dtos.RuleRequest(mr.id(), TransactionType.QR_PAY,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        Dtos.UserResponse u = userService.create(t.getId(),
                new Dtos.UserRequest("+263770000020", null, "Carol", null, "ZW", mr.id(), null));

        var qr = qrService.issue(t.getId(),
                new Dtos.QrIssueRequest(QrToken.SourceType.MERCHANT, mr.id(),
                        TransactionType.QR_PAY, new BigDecimal("25"), "USD", 60));

        var txn = qrService.consume(t.getId(),
                new Dtos.QrConsumeRequest(qr.token(), qr.signature(), u.id(), "ref-qr-1"));
        assertThat(txn.pointsDelta()).isEqualByComparingTo("25");

        // Reuse must fail
        assertThatThrownBy(() -> qrService.consume(t.getId(),
                new Dtos.QrConsumeRequest(qr.token(), qr.signature(), u.id(), "ref-qr-2")))
                .isInstanceOf(LoyaltyException.class);
    }

    @Test
    @Transactional
    void invoicingAggregatesFees() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Pharmacy", "Health", "Gweru", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new BigDecimal("0.01"),
                        new BigDecimal("1.00"),
                        new BigDecimal("0.50")));
        ruleAdminService.createRule(t.getId(),
                new Dtos.RuleRequest(mr.id(), TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        Dtos.UserResponse u = userService.create(t.getId(),
                new Dtos.UserRequest("+263770000030", null, "Dan", null, "ZW", mr.id(), null));

        transactionService.post(t.getId(),
                new Dtos.TransactionRequest(mr.id(), u.id(), TransactionType.PURCHASE,
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
