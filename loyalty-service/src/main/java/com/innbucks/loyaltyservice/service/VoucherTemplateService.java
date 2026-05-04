package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.VoucherTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class VoucherTemplateService {

    private final VoucherTemplateRepository templates;
    private final MerchantService merchants;

    public VoucherTemplateService(VoucherTemplateRepository templates, MerchantService merchants) {
        this.templates = templates;
        this.merchants = merchants;
    }

    public VoucherTemplate create(UUID tenantId, Dtos.VoucherTemplateRequest req) {
        if (req.merchantId() != null) merchants.requireMerchant(tenantId, req.merchantId());
        if (req.valueType() == VoucherTemplate.ValueType.AMOUNT && req.value() == null) {
            throw LoyaltyException.badRequest("MISSING_VALUE", "AMOUNT vouchers require value");
        }
        if (req.valueType() == VoucherTemplate.ValueType.PERCENT && req.value() == null) {
            throw LoyaltyException.badRequest("MISSING_VALUE", "PERCENT vouchers require value");
        }
        if (req.valueType() == VoucherTemplate.ValueType.FREE_ITEM && (req.freeItemSku() == null || req.freeItemSku().isBlank())) {
            throw LoyaltyException.badRequest("MISSING_SKU", "FREE_ITEM vouchers require freeItemSku");
        }
        VoucherTemplate t = new VoucherTemplate();
        t.setTenantId(tenantId);
        t.setMerchantId(req.merchantId());
        t.setName(req.name());
        t.setType(req.type());
        t.setValueType(req.valueType());
        t.setValue(req.value());
        if (req.currency() != null) t.setCurrency(req.currency());
        t.setFreeItemSku(req.freeItemSku());
        t.setUsageLimit(req.usageLimit());
        t.setValidityDays(req.validityDays());
        t.setApplicableOutlets(req.applicableOutlets());
        return templates.save(t);
    }

    @Transactional(readOnly = true)
    public List<VoucherTemplate> list(UUID tenantId) {
        return templates.findByTenantId(tenantId);
    }

    public VoucherTemplate require(UUID tenantId, UUID templateId) {
        VoucherTemplate t = templates.findById(templateId)
                .orElseThrow(() -> LoyaltyException.notFound("voucher template"));
        if (!t.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "template belongs to a different tenant");
        }
        if (!t.isActive()) {
            throw LoyaltyException.badRequest("TEMPLATE_INACTIVE", "template is inactive");
        }
        return t;
    }
}
