package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.PosApiKeyDTOs;
import com.innbucks.userservice.entity.PosApiKey;
import com.innbucks.userservice.exception.PosApiKeyException;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.PosApiKeyRepository;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.security.TokenVersionPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * POS API keys (V51): the machine credential a merchant's point-of-sale backend
 * swaps for a short-lived cashier token, instead of a person signing in with a
 * password and a second factor at every till.
 *
 * <p><b>What a token from a key can do.</b> It is minted through the ordinary
 * {@link JwtUtil} as a {@code SHOP_USER} of the key's merchant (and shop), with
 * no permissions, no phone and no organization. Loyalty therefore treats it as a
 * cashier: earn, burn, voucher redeem, the till checkout, QR status. Every
 * admin action (reversals, voucher issue, rules, staff) still needs a person.
 * {@code userUuid} is the key id, so loyalty records the key as {@code postedBy},
 * and user-service itself refuses the token (there is no such user).
 *
 * <p><b>Revoking is immediate.</b> The token carries the key's
 * {@code token_version}; revoking bumps it and publishes it to the shared
 * {@code auth:tokenver:<key id>} Redis key, which loyalty already checks for
 * every token. A Redis outage falls back to the token's own 15-minute life.
 */
@Slf4j
@Service
public class PosApiKeyService {

    /** Every key starts with this, so a leaked one is recognisable in a log or a repo scan. */
    static final String KEY_PREFIX = "ibpos_";
    private static final int KEY_BYTES = 32;
    private static final int SHOWN_PREFIX_LENGTH = 12;
    private static final int MAX_KEY_LENGTH = 200;
    /** How often a key's last-used time is written, at most. */
    private static final long TOUCH_EVERY_MINUTES = 5;
    static final String EXCHANGE_METRIC = "user.pos_key.exchange";

    private final PosApiKeyRepository keys;
    private final LoyaltyServiceClient loyalty;
    private final JwtUtil jwtUtil;
    private final TokenVersionPublisher tokenVersionPublisher;
    private final AuditService auditService;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final String deploymentCountry;
    private final SecureRandom random = new SecureRandom();

    public PosApiKeyService(PosApiKeyRepository keys,
                            LoyaltyServiceClient loyalty,
                            JwtUtil jwtUtil,
                            TokenVersionPublisher tokenVersionPublisher,
                            AuditService auditService,
                            PlatformTransactionManager transactionManager,
                            MeterRegistry meters,
                            @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.keys = keys;
        this.loyalty = loyalty;
        this.jwtUtil = jwtUtil;
        this.tokenVersionPublisher = tokenVersionPublisher;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.meters = meters;
        this.deploymentCountry = deploymentCountry == null ? "ZW" : deploymentCountry.trim().toUpperCase();
        // Registered at 0 so increase() sees the first refusal (CLAUDE.md, publish_failed).
        for (String outcome : List.of("issued", "rejected", "shop_refused")) {
            exchanges(outcome);
        }
    }

    // ------------------------------------------------------------------
    // Issue, list, revoke (SUPER_ADMIN)
    // ------------------------------------------------------------------

    /**
     * Issue a key. A shop pins the key and decides its merchant; without one the
     * key acts for any shop of the named merchant. The shop is checked with
     * loyalty BEFORE the write, so no transaction waits on the network.
     */
    public PosApiKeyDTOs.Created create(PosApiKeyDTOs.CreateRequest req, String actor, AuditContext context) {
        UUID merchantId = req.merchantId();
        UUID shopId = req.shopId();
        if (shopId != null) {
            UUID shopMerchant = merchantOfShop(shopId).orElseThrow(() -> PosApiKeyException.badRequest(
                    "pos_key_shop_unknown", "We couldn't find that shop in loyalty."));
            if (merchantId != null && !merchantId.equals(shopMerchant)) {
                throw PosApiKeyException.badRequest("pos_key_merchant_mismatch",
                        "That shop belongs to a different merchant.");
            }
            merchantId = shopMerchant;
        } else if (merchantId == null) {
            throw PosApiKeyException.badRequest("pos_key_merchant_required",
                    "Name the merchant (merchantId) or the shop (shopId) the key acts for.");
        }
        List<String> allowedIps = PosIpAllowList.normalise(req.allowedIps());

        String key = newKey();
        PosApiKey row = PosApiKey.builder()
                .id(UUID.randomUUID())
                .name(req.name().trim())
                .keyPrefix(key.substring(0, SHOWN_PREFIX_LENGTH))
                .keyHash(sha256(key))
                .merchantId(merchantId)
                .shopId(shopId)
                .allowedIps(allowedIps.isEmpty() ? null : String.join(",", allowedIps))
                .createdBy(actor)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        tx.executeWithoutResult(status -> {
            keys.saveAndFlush(row);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("name", row.getName());
            metadata.put("keyPrefix", row.getKeyPrefix());
            metadata.put("merchantId", row.getMerchantId().toString());
            if (row.getShopId() != null) metadata.put("shopId", row.getShopId().toString());
            if (!allowedIps.isEmpty()) metadata.put("allowedIps", allowedIps);
            // Last statement: a failed audit refuses the key (503 audit_unavailable).
            auditService.recordRequired(AuditEventType.POS_API_KEY_CREATED, actor, AuditService.ACTOR_TYPE_USER,
                    row.getId().toString(), AuditService.TARGET_TYPE_POS_API_KEY, metadata, orNone(context));
        });
        log.info("POS API key issued id={} prefix={} merchantId={} shopId={} ipRestricted={} by={}",
                row.getId(), row.getKeyPrefix(), row.getMerchantId(), row.getShopId(), !allowedIps.isEmpty(), actor);
        return new PosApiKeyDTOs.Created(row.getId(), row.getName(), key, row.getKeyPrefix(),
                row.getMerchantId(), row.getShopId(), allowedIps, row.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public PosApiKeyDTOs.KeyPage list(UUID merchantId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<PosApiKey> found = merchantId == null
                ? keys.findAllByOrderByCreatedAtDesc(pageable)
                : keys.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
        return new PosApiKeyDTOs.KeyPage(found.getContent().stream().map(PosApiKeyService::view).toList(),
                found.getTotalElements(), found.getTotalPages(), found.getNumber(), found.getSize());
    }

    /**
     * Revoke a key. Its live tokens stop working in loyalty at once (the bumped
     * version is published after commit); a new exchange is refused.
     */
    @Transactional
    public PosApiKeyDTOs.KeyView revoke(UUID id, String note, String actor, AuditContext context) {
        PosApiKey key = keys.findById(id).orElseThrow(() -> new PosApiKeyException(HttpStatus.NOT_FOUND,
                "pos_key_not_found", "We couldn't find that API key."));
        if (key.isRevoked() || keys.revoke(id, LocalDateTime.now(ZoneOffset.UTC), actor) == 0) {
            throw new PosApiKeyException(HttpStatus.CONFLICT, "pos_key_already_revoked",
                    "This API key is already revoked.");
        }
        PosApiKey revoked = keys.findById(id).orElseThrow();
        tokenVersionPublisher.publishAfterCommit(revoked.getId(), revoked.getTokenVersion());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", revoked.getName());
        metadata.put("keyPrefix", revoked.getKeyPrefix());
        metadata.put("merchantId", revoked.getMerchantId().toString());
        String cleanNote = MfaService.cleanNote(note);
        if (cleanNote != null) metadata.put("note", cleanNote);
        auditService.recordRequired(AuditEventType.POS_API_KEY_REVOKED, actor, AuditService.ACTOR_TYPE_USER,
                revoked.getId().toString(), AuditService.TARGET_TYPE_POS_API_KEY, metadata, orNone(context));
        log.info("POS API key revoked id={} prefix={} by={}", revoked.getId(), revoked.getKeyPrefix(), actor);
        return view(revoked);
    }

    // ------------------------------------------------------------------
    // The exchange (the POS backend)
    // ------------------------------------------------------------------

    /**
     * Swap a key for a cashier access token. An unknown key, a revoked key and
     * an address outside the key's allow-list are one opaque 401; which it was
     * goes to the log and the {@code reason} tag, never to the caller.
     *
     * @param requestedShop the shop the token acts for: required for a
     *        merchant-wide key that will act at a shop, and must match (or be
     *        null) for a shop-pinned key
     * @param clientIp the caller's address as nginx saw it ({@code X-Real-IP}),
     *        or null when it did not come through the edge
     */
    public PosApiKeyDTOs.TokenResponse exchange(String rawKey, UUID requestedShop, String clientIp) {
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > MAX_KEY_LENGTH
                || !rawKey.trim().startsWith(KEY_PREFIX)) {
            throw rejected("malformed", null);
        }
        PosApiKey key = keys.findByKeyHash(sha256(rawKey.trim())).orElse(null);
        if (key == null) throw rejected("unknown", null);
        if (key.isRevoked()) throw rejected("revoked", key);
        if (!PosIpAllowList.fromColumn(key.getAllowedIps()).allows(clientIp)) {
            throw rejected("ip_not_allowed", key);
        }

        UUID shopId = key.getShopId();
        if (shopId != null) {
            if (requestedShop != null && !requestedShop.equals(shopId)) throw shopRefused(key, requestedShop);
        } else if (requestedShop != null) {
            // Merchant-wide key: the shop must be one of the key's merchant's. An
            // unanswerable lookup refuses, never assumes.
            if (!merchantOfShop(requestedShop).map(key.getMerchantId()::equals).orElse(false)) {
                throw shopRefused(key, requestedShop);
            }
            shopId = requestedShop;
        }

        String token = jwtUtil.generateToken("pos-key:" + key.getId(), List.of("SHOP_USER"), List.of(), List.of(),
                0, false, null, key.getMerchantId(), shopId, null, null, null,
                key.getTokenVersion(), null, key.getId(), null, false, null, deploymentCountry);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            tx.executeWithoutResult(status -> keys.touch(key.getId(), now, now.minusMinutes(TOUCH_EVERY_MINUTES)));
        } catch (RuntimeException e) {
            // Bookkeeping only: a failed stamp must not refuse a valid key.
            log.warn("POS API key last-used stamp failed id={} error={}", key.getId(), e.getMessage());
        }
        exchanges("issued").increment();
        return new PosApiKeyDTOs.TokenResponse(token, "Bearer", jwtUtil.accessTokenTtlMillis() / 1000,
                key.getMerchantId(), shopId);
    }

    // ------------------------------------------------------------------

    private Optional<UUID> merchantOfShop(UUID shopId) {
        return loyalty.findShop(shopId)
                .map(LoyaltyServiceClient.ShopLookupResponse::merchantId)
                .flatMap(PosApiKeyService::uuid);
    }

    private PosApiKeyException rejected(String reason, PosApiKey key) {
        log.info("POS API key rejected reason={} id={}", reason, key == null ? "-" : key.getId());
        meters.counter(EXCHANGE_METRIC, "outcome", "rejected", "reason", reason).increment();
        return PosApiKeyException.rejected();
    }

    private PosApiKeyException shopRefused(PosApiKey key, UUID requestedShop) {
        log.info("POS API key refused a shop id={} shopId={}", key.getId(), requestedShop);
        exchanges("shop_refused").increment();
        return new PosApiKeyException(HttpStatus.FORBIDDEN, "pos_shop_not_allowed",
                "This API key can't act for that shop.");
    }

    private Counter exchanges(String outcome) {
        return meters.counter(EXCHANGE_METRIC, "outcome", outcome, "reason", "none");
    }

    private String newKey() {
        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);
        String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Arrays.fill(bytes, (byte) 0);
        return key;
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static PosApiKeyDTOs.KeyView view(PosApiKey k) {
        List<String> ips = k.getAllowedIps() == null || k.getAllowedIps().isBlank()
                ? List.of() : List.of(k.getAllowedIps().split(","));
        return new PosApiKeyDTOs.KeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.getMerchantId(),
                k.getShopId(), ips, k.isRevoked() ? "REVOKED" : "ACTIVE", k.getCreatedBy(), k.getCreatedAt(),
                k.getLastUsedAt(), k.getRevokedAt(), k.getRevokedBy());
    }

    private static Optional<UUID> uuid(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(s.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static AuditContext orNone(AuditContext context) {
        return context == null ? AuditContext.none() : context;
    }
}
