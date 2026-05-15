package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.service.ShopCheckoutService;
import com.innbucks.loyaltyservice.service.UserService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service-to-service endpoints consumed by other backends (today: user-service
 * at login + shop-staff creation). Gated by a shared-secret header rather than
 * the user JWT — the caller is another microservice, not a logged-in user.
 * Hidden from Swagger.
 */
@RestController
@RequestMapping("/loyalty/internal")
@Slf4j
@Hidden
public class InternalMerchantLookupController {

    private final MerchantRepository merchants;
    private final ShopRepository shops;
    private final UserService userService;
    private final ShopCheckoutService shopCheckoutService;
    private final String expectedToken;

    public InternalMerchantLookupController(MerchantRepository merchants,
                                            ShopRepository shops,
                                            UserService userService,
                                            ShopCheckoutService shopCheckoutService,
                                            @Value("${innbucks.internal-api-token:}") String expectedToken) {
        this.merchants = merchants;
        this.shops = shops;
        this.userService = userService;
        this.shopCheckoutService = shopCheckoutService;
        this.expectedToken = expectedToken;
    }

    @GetMapping("/merchants/by-admin")
    public ResponseEntity<?> byAdminEmail(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                          @RequestParam("email") String email) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "email is required"));
        }
        Optional<Merchant> hit = merchants.findFirstByAdminEmailOrderByCreatedAtAsc(email);
        if (hit.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        UUID merchantId = hit.get().getId();
        log.debug("Internal lookup resolved adminEmail={} -> merchantId={}", email, merchantId);
        return ResponseEntity.ok(Map.of("merchantId", merchantId));
    }

    @GetMapping("/shops/{id}")
    public ResponseEntity<?> getShop(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                     @PathVariable UUID id) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<Shop> hit = shops.findById(id);
        if (hit.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Shop s = hit.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", s.getId());
        body.put("merchantId", s.getMerchantId());
        body.put("tenantId", s.getTenantId());
        body.put("status", s.getStatus().name());
        return ResponseEntity.ok(body);
    }

    /**
     * Promote-on-registration webhook. Called by user-service the moment a
     * phone completes signup so every PENDING LoyaltyUser matching that phone
     * — across all tenants — flips to ACTIVE. Idempotent: replays are safe and
     * report how many rows actually changed.
     *
     * <p>Body: {@code {"phoneNumber": "+263771234567"}}.
     */
    @org.springframework.web.bind.annotation.PostMapping("/users/promote")
    public ResponseEntity<?> promote(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                     @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String phone = body == null ? null : body.get("phoneNumber");
        if (phone == null || phone.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "phoneNumber is required"));
        }
        int promoted = userService.promoteByPhone(phone);
        log.info("Promoted {} PENDING LoyaltyUser(s) for phone={}", promoted, phone);
        return ResponseEntity.ok(Map.of("phoneNumber", phone, "promoted", promoted));
    }

    /**
     * Shop checkout: optional earn (cash → PURCHASE) and optional burn
     * (points → REDEMPTION), atomic. Called by payment-service when a customer
     * pays at a shop with cash, points, or a mix of both.
     *
     * <p>Body: {@code {"shopId": "...", "phoneNumber": "...",
     * "cashAmount": 10.00, "pointsAmount": 200, "reference": "POS-..."}}.
     * At least one of {@code cashAmount} or {@code pointsAmount} must be > 0.
     */
    @PostMapping("/shop-checkout")
    public ResponseEntity<?> shopCheckout(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                          @RequestBody Map<String, Object> body) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            UUID shopId = body.get("shopId") == null ? null : UUID.fromString(body.get("shopId").toString());
            String phone = body.get("phoneNumber") == null ? null : body.get("phoneNumber").toString();
            BigDecimal cash = asBigDecimal(body.get("cashAmount"));
            BigDecimal points = asBigDecimal(body.get("pointsAmount"));
            String reference = body.get("reference") == null ? null : body.get("reference").toString();

            ShopCheckoutService.Result r = shopCheckoutService.checkout(shopId, phone, cash, points, reference);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("shopId", r.shopId());
            resp.put("merchantId", r.merchantId());
            resp.put("tenantId", r.tenantId());
            resp.put("loyaltyUserId", r.loyaltyUserId());
            resp.put("cashAmount", r.cashAmount());
            resp.put("pointsRedeemed", r.pointsRedeemed());
            resp.put("pointsEarned", r.pointsEarned());
            resp.put("walletBalanceAfter", r.walletBalanceAfter());
            resp.put("purchaseTransactionId", r.purchaseTransactionId());
            resp.put("redemptionTransactionId", r.redemptionTransactionId());
            return ResponseEntity.ok(resp);
        } catch (LoyaltyException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getCode(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_INPUT", "message", e.getMessage()));
        }
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        return new BigDecimal(s);
    }

    private boolean authorized(String presented) {
        if (expectedToken == null || expectedToken.isBlank()) {
            // No token configured: refuse all internal calls rather than fail-open.
            log.warn("Internal API token is not configured; rejecting call");
            return false;
        }
        return expectedToken.equals(presented);
    }
}
