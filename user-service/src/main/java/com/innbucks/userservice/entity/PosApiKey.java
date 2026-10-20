package com.innbucks.userservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A machine credential for a merchant's point-of-sale backend (V51). Exchanged
 * at {@code POST /auth/pos/token} for a short-lived SHOP_USER access token, so a
 * POS integration needs no human login and no second factor.
 *
 * <p>Only the SHA-256 of the key is stored ({@link #keyHash}); the key is shown
 * once, at creation. {@link #shopId} null means the key may act for any shop of
 * {@link #merchantId}, naming the shop when it asks for a token.
 *
 * <p>{@link #tokenVersion} is written only by {@code PosApiKeyRepository.revoke}
 * (an atomic increment); the column is not updatable through the entity.
 */
@Entity
@Table(name = "pos_api_keys")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosApiKey {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    /** The key's first characters, so an operator can tell keys apart. Not a secret. */
    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    /** SHA-256 hex of the raw key. Never logged, never returned. */
    @ToString.Exclude
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    /** Null: any shop of the merchant, chosen per token. */
    @Column(name = "shop_id")
    private UUID shopId;

    /** Comma-separated IPv4/IPv6 addresses or CIDR ranges; null = any address. */
    @Column(name = "allowed_ips", length = 1000)
    private String allowedIps;

    @Column(name = "token_version", nullable = false, insertable = false, updatable = false)
    private long tokenVersion;

    @Column(name = "created_by", nullable = false, length = 254)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by", length = 254)
    private String revokedBy;

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
