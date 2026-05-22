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

    public Dtos.ShopResponse create(UUID tenantId, Dtos.ShopRequest req) {
        Merchant m = merchants.requireMerchant(tenantId, req.merchantId());

        Shop s = new Shop();
        s.setTenantId(tenantId);
        s.setMerchantId(m.getId());
        s.setName(req.name());
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

    public Dtos.ShopResponse update(UUID tenantId, UUID shopId, Dtos.ShopRequest req) {
        Shop s = requireShop(tenantId, shopId);
        s.setName(req.name());
        if (req.address() != null) s.setAddress(req.address());
        return toResponse(s);
    }

    public Dtos.ShopResponse setActive(UUID tenantId, UUID shopId, boolean active) {
        Shop s = requireShop(tenantId, shopId);
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

    public static Dtos.ShopResponse toResponse(Shop s) {
        return new Dtos.ShopResponse(s.getId(), s.getTenantId(), s.getMerchantId(),
                s.getName(), s.getAddress(), s.getStatus(), s.getCreatedAt());
    }
}
