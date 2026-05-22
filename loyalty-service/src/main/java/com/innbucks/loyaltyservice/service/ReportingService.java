package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReportingService {

    private final TenantRepository tenants;
    private final MerchantRepository merchants;
    private final LoyaltyUserRepository users;
    private final LoyaltyTransactionRepository transactions;
    private final VoucherRepository vouchers;
    private final InvoiceRepository invoices;
    private final FraudAttemptRepository fraud;
    private final CampaignRepository campaigns;
    private final WalletRepository wallets;

    public ReportingService(TenantRepository tenants, MerchantRepository merchants,
                            LoyaltyUserRepository users,
                            LoyaltyTransactionRepository transactions,
                            VoucherRepository vouchers,
                            InvoiceRepository invoices,
                            FraudAttemptRepository fraud,
                            CampaignRepository campaigns,
                            WalletRepository wallets) {
        this.tenants = tenants;
        this.merchants = merchants;
        this.users = users;
        this.transactions = transactions;
        this.vouchers = vouchers;
        this.invoices = invoices;
        this.fraud = fraud;
        this.campaigns = campaigns;
        this.wallets = wallets;
    }

    public Dtos.OperatorDashboard operator() {
        Instant startOfDay = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfDay = startOfDay.plusSeconds(86_400);
        Instant in7 = startOfDay.plusSeconds(7 * 86_400);
        Instant in30 = startOfDay.plusSeconds(30 * 86_400);
        Instant since24h = Instant.now().minusSeconds(86_400);

        long totalTenants = tenants.count();
        long activeMerchants = merchants.findAll().stream()
                .filter(m -> m.getStatus() == Merchant.Status.ACTIVE).count();
        long txnsToday = transactions.countSince(startOfDay);

        BigDecimal pointsIssuedToday = BigDecimal.ZERO;
        BigDecimal pointsRedeemedToday = BigDecimal.ZERO;
        long vouchersIssuedToday = 0;
        long vouchersRedeemedToday = 0;
        for (Merchant m : merchants.findAll()) {
            pointsIssuedToday = pointsIssuedToday.add(transactions.sumPointsIssued(m.getId(), startOfDay, endOfDay));
            pointsRedeemedToday = pointsRedeemedToday.add(transactions.sumPointsRedeemed(m.getId(), startOfDay, endOfDay));
            vouchersIssuedToday += vouchers.countByMerchantIdAndIssuedAtBetween(m.getId(), startOfDay, endOfDay);
            vouchersRedeemedToday += vouchers.countByMerchantIdAndRedeemedAtBetween(m.getId(), startOfDay, endOfDay);
        }

        long fraudAttempts = fraud.countByCreatedAtAfter(since24h);
        long invoicesPending = invoices.countByStatus(Invoice.Status.PENDING);
        long invoicesPaid = invoices.countByStatus(Invoice.Status.PAID);

        long expiringIn7 = vouchers.findExpired(in7).size();
        long expiringIn30 = vouchers.findExpired(in30).size();

        return new Dtos.OperatorDashboard(totalTenants, activeMerchants, txnsToday,
                vouchersIssuedToday, vouchersRedeemedToday,
                pointsIssuedToday, pointsRedeemedToday, fraudAttempts,
                invoicesPending, invoicesPaid, expiringIn7, expiringIn30);
    }

    public Dtos.TenantDashboard tenant(UUID tenantId) {
        long merchantCount = merchants.findByTenantId(tenantId).size();
        long activeCampaigns = campaigns.findByTenantId(tenantId).stream()
                .filter(Campaign::isActive).count();
        long outstanding = vouchers.countByTenantIdAndStatus(tenantId, Voucher.Status.ISSUED)
                + vouchers.countByTenantIdAndStatus(tenantId, Voucher.Status.DELIVERED)
                + vouchers.countByTenantIdAndStatus(tenantId, Voucher.Status.VIEWED)
                + vouchers.countByTenantIdAndStatus(tenantId, Voucher.Status.PARTIALLY_USED);
        long expired = vouchers.countByTenantIdAndStatus(tenantId, Voucher.Status.EXPIRED);
        BigDecimal totalBalance = wallets.sumBalanceByTenant(tenantId);
        if (totalBalance == null) totalBalance = BigDecimal.ZERO;
        long pending = invoices.findByTenantIdAndStatus(tenantId, Invoice.Status.PENDING).size();
        return new Dtos.TenantDashboard(tenantId, merchantCount, activeCampaigns,
                outstanding, expired, totalBalance, pending);
    }

    public Dtos.MerchantDashboard merchant(UUID merchantId) {
        Instant from = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = from.plusSeconds(86_400);
        Instant since24h = Instant.now().minusSeconds(86_400);
        long today = vouchers.countByMerchantIdAndRedeemedAtBetween(merchantId, from, to);
        long issued = vouchers.countByMerchantIdAndIssuedAtBetween(merchantId, from, to);
        long redeemed = today;
        BigDecimal pointsIssued = transactions.sumPointsIssued(merchantId, from, to);
        BigDecimal pointsRedeemed = transactions.sumPointsRedeemed(merchantId, from, to);
        long fraudAlerts = fraud.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(f -> merchantId.equals(f.getMerchantId()))
                .filter(f -> f.getCreatedAt().isAfter(since24h))
                .count();
        Merchant m = merchants.findById(merchantId).orElse(null);
        LocalDate nextInvoice = m == null ? null
                : (m.getBillingCycle() == Merchant.BillingCycle.WEEKLY
                    ? LocalDate.now().plusWeeks(1).with(java.time.DayOfWeek.MONDAY)
                    : LocalDate.now().withDayOfMonth(1).plusMonths(1));
        BigDecimal estimatedInvoice = m == null ? BigDecimal.ZERO
                : m.getFeePerVoucherIssued().multiply(BigDecimal.valueOf(issued))
                    .add(m.getFeePerVoucherRedeemed().multiply(BigDecimal.valueOf(redeemed)));
        return new Dtos.MerchantDashboard(merchantId, today, issued, redeemed,
                pointsIssued, pointsRedeemed, fraudAlerts, nextInvoice, estimatedInvoice);
    }

    public Map<String, Long> transactionMix(UUID tenantId, UUID merchantId,
                                            LocalDate from, LocalDate to) {
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Long> out = new HashMap<>();
        List<Object[]> rows = transactions.countByType(tenantId, merchantId, fromInstant, toInstant);
        for (Object[] r : rows) {
            out.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        }
        return out;
    }

    public List<Dtos.FraudAttemptResponse> recentFraud(UUID tenantId) {
        return fraud.findTop100ByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(f -> new Dtos.FraudAttemptResponse(f.getId(), f.getVoucherCode(),
                        f.getMerchantId(), f.getReason().name(), f.getDetail(),
                        f.getDeviceFingerprint(), f.getCreatedAt()))
                .toList();
    }

    public String csv(UUID tenantId, UUID merchantId, LocalDate from, LocalDate to) {
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder("type,count\n");
        transactionMix(tenantId, merchantId, from, to).forEach((k, v) ->
                sb.append(k).append(',').append(v).append('\n'));
        sb.append("# generated at ").append(Instant.now()).append(' ').append(fromInstant).append('-').append(toInstant);
        return sb.toString();
    }
}
