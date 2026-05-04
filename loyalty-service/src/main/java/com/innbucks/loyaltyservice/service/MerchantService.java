package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MerchantService {

    private final MerchantRepository merchants;

    public MerchantService(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    public Dtos.MerchantResponse create(UUID tenantId, Dtos.MerchantRequest req) {
        Merchant m = new Merchant();
        m.setTenantId(tenantId);
        m.setName(req.name());
        m.setCategory(req.category());
        m.setLocation(req.location());
        if (req.currency() != null) m.setCurrency(req.currency());
        if (req.billingCycle() != null) m.setBillingCycle(req.billingCycle());
        if (req.feePerPointIssued() != null) m.setFeePerPointIssued(req.feePerPointIssued());
        if (req.feePerVoucherIssued() != null) m.setFeePerVoucherIssued(req.feePerVoucherIssued());
        if (req.feePerVoucherRedeemed() != null) m.setFeePerVoucherRedeemed(req.feePerVoucherRedeemed());
        merchants.save(m);
        return toResponse(m);
    }

    @Transactional(readOnly = true)
    public List<Dtos.MerchantResponse> list(UUID tenantId) {
        return merchants.findByTenantId(tenantId).stream().map(MerchantService::toResponse).toList();
    }

    public Merchant requireMerchant(UUID tenantId, UUID merchantId) {
        Merchant m = merchants.findById(merchantId)
                .orElseThrow(() -> LoyaltyException.notFound("merchant"));
        if (!m.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "merchant belongs to a different tenant");
        }
        return m;
    }

    public Dtos.MerchantResponse setActive(UUID tenantId, UUID merchantId, boolean active) {
        Merchant m = requireMerchant(tenantId, merchantId);
        m.setStatus(active ? Merchant.Status.ACTIVE : Merchant.Status.INACTIVE);
        return toResponse(m);
    }

    public BigDecimal feeForPoints(Merchant m, BigDecimal pointsIssued) {
        return m.getFeePerPointIssued().multiply(pointsIssued);
    }

    public static Dtos.MerchantResponse toResponse(Merchant m) {
        return new Dtos.MerchantResponse(m.getId(), m.getTenantId(), m.getName(),
                m.getCategory(), m.getCurrency(), m.getBillingCycle(), m.getStatus());
    }
}
