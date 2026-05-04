package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Transactional
public class InvoicingService {

    private final InvoiceRepository invoices;
    private final MerchantRepository merchants;
    private final LoyaltyTransactionRepository transactions;
    private final VoucherRepository vouchers;
    private final LoyaltyProperties props;

    public InvoicingService(InvoiceRepository invoices, MerchantRepository merchants,
                            LoyaltyTransactionRepository transactions,
                            VoucherRepository vouchers,
                            LoyaltyProperties props) {
        this.invoices = invoices;
        this.merchants = merchants;
        this.transactions = transactions;
        this.vouchers = vouchers;
        this.props = props;
    }

    public Invoice generate(Merchant m, LocalDate periodStart, LocalDate periodEnd) {
        invoices.findByMerchantIdAndPeriodStartAndPeriodEnd(m.getId(), periodStart, periodEnd)
                .ifPresent(existing -> {
                    throw LoyaltyException.conflict("INVOICE_EXISTS",
                            "invoice already exists for this merchant + period");
                });
        Instant from = periodStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = periodEnd.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        BigDecimal pointsIssued = transactions.sumPointsIssued(m.getId(), from, to);
        BigDecimal pointsRedeemed = transactions.sumPointsRedeemed(m.getId(), from, to);
        long voucherIssued = vouchers.countByMerchantIdAndIssuedAtBetween(m.getId(), from, to);
        long voucherRedeemed = vouchers.countByMerchantIdAndRedeemedAtBetween(m.getId(), from, to);

        BigDecimal feePoints = m.getFeePerPointIssued().multiply(pointsIssued);
        BigDecimal feeVoucherIssued = m.getFeePerVoucherIssued().multiply(BigDecimal.valueOf(voucherIssued));
        BigDecimal feeVoucherRedeemed = m.getFeePerVoucherRedeemed().multiply(BigDecimal.valueOf(voucherRedeemed));
        BigDecimal total = feePoints.add(feeVoucherIssued).add(feeVoucherRedeemed);

        Invoice inv = new Invoice();
        inv.setTenantId(m.getTenantId());
        inv.setMerchantId(m.getId());
        inv.setPeriodStart(periodStart);
        inv.setPeriodEnd(periodEnd);
        inv.setPointsIssued(pointsIssued);
        inv.setPointsRedeemed(pointsRedeemed);
        inv.setVouchersIssued(voucherIssued);
        inv.setVouchersRedeemed(voucherRedeemed);
        inv.setTotalAmount(total);
        inv.setCurrency(m.getCurrency());
        inv.setInvoiceNumber(nextInvoiceNumber());
        return invoices.save(inv);
    }

    private String nextInvoiceNumber() {
        return props.invoice().prefix() + "-"
                + Instant.now().toEpochMilli() + "-"
                + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    public Invoice markPaid(UUID tenantId, UUID invoiceId) {
        Invoice inv = invoices.findById(invoiceId)
                .orElseThrow(() -> LoyaltyException.notFound("invoice"));
        if (!inv.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        }
        if (inv.getStatus() == Invoice.Status.PAID) {
            return inv;
        }
        inv.setStatus(Invoice.Status.PAID);
        inv.setPaidAt(Instant.now());
        return inv;
    }

    @Transactional(readOnly = true)
    public List<Dtos.InvoiceResponse> listForMerchant(UUID tenantId, UUID merchantId) {
        Merchant m = merchants.findById(merchantId)
                .orElseThrow(() -> LoyaltyException.notFound("merchant"));
        if (!m.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        }
        return invoices.findByMerchantIdOrderByPeriodEndDesc(merchantId).stream()
                .map(InvoicingService::toResponse).toList();
    }

    public LocalDate previousPeriodStart(LocalDate today, Merchant.BillingCycle cycle) {
        return cycle == Merchant.BillingCycle.WEEKLY
                ? today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                : today.withDayOfMonth(1).minusMonths(1);
    }

    public LocalDate previousPeriodEnd(LocalDate today, Merchant.BillingCycle cycle) {
        return cycle == Merchant.BillingCycle.WEEKLY
                ? today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY))
                : today.withDayOfMonth(1).minusDays(1);
    }

    /** Run the periodic job: generate one PENDING invoice per active merchant for the previous period. */
    public int runPeriodicForAllMerchants(LocalDate today) {
        int created = 0;
        for (Merchant m : merchants.findAll()) {
            if (m.getStatus() != Merchant.Status.ACTIVE) continue;
            LocalDate start = previousPeriodStart(today, m.getBillingCycle());
            LocalDate end = previousPeriodEnd(today, m.getBillingCycle());
            if (invoices.findByMerchantIdAndPeriodStartAndPeriodEnd(m.getId(), start, end).isEmpty()) {
                generate(m, start, end);
                created++;
            }
        }
        return created;
    }

    public static Dtos.InvoiceResponse toResponse(Invoice inv) {
        return new Dtos.InvoiceResponse(inv.getId(), inv.getInvoiceNumber(), inv.getMerchantId(),
                inv.getPeriodStart(), inv.getPeriodEnd(), inv.getPointsIssued(),
                inv.getPointsRedeemed(), inv.getVouchersIssued(), inv.getVouchersRedeemed(),
                inv.getTotalAmount(), inv.getCurrency(), inv.getStatus().name(), inv.getPaidAt());
    }
}
