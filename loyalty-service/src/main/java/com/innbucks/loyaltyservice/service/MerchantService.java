package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class MerchantService {

    private final MerchantRepository merchants;
    private final UserServiceClient userServiceClient;

    public MerchantService(MerchantRepository merchants, UserServiceClient userServiceClient) {
        this.merchants = merchants;
        this.userServiceClient = userServiceClient;
    }

    public Dtos.MerchantResponse create(UUID tenantId, Dtos.MerchantRequest req) {
        Merchant m = new Merchant();
        m.setTenantId(tenantId);
        m.setName(req.name());
        m.setCategory(req.category());
        if (req.currency() != null) m.setCurrency(req.currency());
        if (req.billingCycle() != null) m.setBillingCycle(req.billingCycle());
        if (req.feePerVoucherIssued() != null) m.setFeePerVoucherIssued(req.feePerVoucherIssued());
        if (req.feePerVoucherRedeemed() != null) m.setFeePerVoucherRedeemed(req.feePerVoucherRedeemed());
        m.setAdminEmail(callerEmail());
        merchants.save(m);
        return toResponse(m);
    }

    private static String callerEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        String name = auth.getName();
        return name == null || name.isBlank() ? null : name;
    }

    @Transactional(readOnly = true)
    public List<Dtos.MerchantResponse> list(UUID tenantId) {
        return merchants.findByTenantId(tenantId).stream().map(MerchantService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.MerchantResponse> list(UUID tenantId, Pageable pageable) {
        return list(tenantId, pageable, false);
    }

    /**
     * Tenant-scoped merchant page. When {@code unassigned} is true, the result
     * is filtered to merchants that do NOT yet have any MERCHANT_ADMIN user
     * attached — what the FE shows a registering merchant admin so they can
     * pick a yet-unclaimed merchant to bind themselves to.
     *
     * <p>The exclusion list is fetched from user-service on demand
     * ({@code GET /users/internal/merchants/assigned}). If user-service is
     * unreachable, we let the exception bubble — silently falling back to "all
     * merchants" would show the FE merchants that already have admins and
     * defeat the picker's whole purpose.
     */
    @Transactional(readOnly = true)
    public Page<Dtos.MerchantResponse> list(UUID tenantId, Pageable pageable, boolean unassigned) {
        if (!unassigned) {
            return merchants.findByTenantId(tenantId, pageable).map(MerchantService::toResponse);
        }
        Set<UUID> assigned = userServiceClient.assignedMerchantIds();
        Page<Merchant> page = assigned.isEmpty()
                // Hibernate refuses to emit `IN ()`; the no-exclusion case is
                // semantically identical to the unfiltered listing.
                ? merchants.findByTenantId(tenantId, pageable)
                : merchants.findByTenantIdAndIdNotIn(tenantId, assigned, pageable);
        return page.map(MerchantService::toResponse);
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

    public static Dtos.MerchantResponse toResponse(Merchant m) {
        return new Dtos.MerchantResponse(m.getId(), m.getTenantId(), m.getName(),
                m.getCategory(), m.getCurrency(), m.getBillingCycle(), m.getStatus());
    }
}
