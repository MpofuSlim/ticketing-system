package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ShopService {

    private final ShopRepository shops;
    private final MerchantService merchants;

    public ShopService(ShopRepository shops, MerchantService merchants) {
        this.shops = shops;
        this.merchants = merchants;
    }

    public Dtos.ShopResponse create(UUID tenantId, UUID callerMerchantId, Dtos.ShopRequest req) {
        if (callerMerchantId == null) {
            throw LoyaltyException.badRequest("MERCHANT_REQUIRED",
                    "caller has no merchant scope; only MERCHANT_ADMIN tokens can create shops");
        }
        Merchant m = merchants.requireMerchant(tenantId, callerMerchantId);

        Shop s = new Shop();
        s.setTenantId(tenantId);
        s.setMerchantId(m.getId());
        s.setName(req.name());
        s.setCode(req.code());
        s.setAddress(req.address());
        shops.save(s);
        return toResponse(s);
    }

    @Transactional(readOnly = true)
    public Page<Dtos.ShopResponse> list(UUID tenantId, UUID merchantFilter, Pageable pageable) {
        Page<Shop> page = merchantFilter == null
                ? shops.findByTenantId(tenantId, pageable)
                : shops.findByTenantIdAndMerchantId(tenantId, merchantFilter, pageable);
        return page.map(ShopService::toResponse);
    }

    @Transactional(readOnly = true)
    public List<Dtos.ShopResponse> listForMerchant(UUID tenantId, UUID merchantId) {
        merchants.requireMerchant(tenantId, merchantId);
        return shops.findByTenantIdAndMerchantId(tenantId, merchantId)
                .stream().map(ShopService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Dtos.ShopResponse get(UUID tenantId, UUID shopId) {
        return toResponse(requireShop(tenantId, shopId));
    }

    public Dtos.ShopResponse update(UUID tenantId, UUID callerMerchantId, UUID shopId,
                                    Dtos.ShopRequest req) {
        Shop s = requireShop(tenantId, shopId);
        guardCallerOwnsShop(s, callerMerchantId);
        s.setName(req.name());
        if (req.code() != null) s.setCode(req.code());
        if (req.address() != null) s.setAddress(req.address());
        return toResponse(s);
    }

    public Dtos.ShopResponse setActive(UUID tenantId, UUID callerMerchantId, UUID shopId, boolean active) {
        Shop s = requireShop(tenantId, shopId);
        guardCallerOwnsShop(s, callerMerchantId);
        s.setStatus(active ? Shop.Status.ACTIVE : Shop.Status.INACTIVE);
        return toResponse(s);
    }

    public Shop requireShop(UUID tenantId, UUID shopId) {
        Shop s = shops.findById(shopId).orElseThrow(() -> LoyaltyException.notFound("shop"));
        if (!s.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "shop belongs to a different tenant");
        }
        return s;
    }

    private void guardCallerOwnsShop(Shop s, UUID callerMerchantId) {
        if (callerMerchantId != null && !callerMerchantId.equals(s.getMerchantId())) {
            throw LoyaltyException.forbidden("WRONG_MERCHANT",
                    "shop belongs to a different merchant");
        }
    }

    public static Dtos.ShopResponse toResponse(Shop s) {
        return new Dtos.ShopResponse(s.getId(), s.getTenantId(), s.getMerchantId(),
                s.getName(), s.getCode(), s.getAddress(), s.getStatus(), s.getCreatedAt());
    }
}
