package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** POS API keys (V51): issue, list, revoke, and the key-for-token exchange. */
public final class PosApiKeyDTOs {

    private PosApiKeyDTOs() {}

    @Schema(name = "PosApiKeyCreateRequest")
    public record CreateRequest(
            @NotBlank(message = "name is required")
            @Size(max = 100, message = "name must not exceed 100 characters")
            @Schema(example = "GAAP cloud - Chicken Inn")
            String name,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Loyalty merchant the key acts for. Required unless shopId is given, in which "
                            + "case the merchant is taken from the shop.")
            UUID merchantId,
            @Schema(example = "11111111-aaaa-bbbb-cccc-222222222222", nullable = true,
                    description = "Pin the key to one shop. Omit for a merchant-wide key, which names the shop "
                            + "each time it asks for a token.")
            UUID shopId,
            @Size(max = 20, message = "at most 20 allowed addresses")
            @Schema(example = "[\"196.27.104.10\", \"41.190.32.0/24\"]", nullable = true,
                    description = "IPv4/IPv6 addresses or CIDR ranges the key may be used from. Omit or leave "
                            + "empty to allow any address.")
            List<String> allowedIps) {}

    /** Returned once, at creation: the only time {@code key} is ever shown. */
    @Schema(name = "PosApiKeyCreated")
    public record Created(
            @Schema(example = "3c9d8e7f-1a2b-4c3d-8e9f-0a1b2c3d4e5f") UUID id,
            @Schema(example = "GAAP cloud - Chicken Inn") String name,
            @Schema(example = "ibpos_k3Zr9QxT0b1LmN8pWcVd2eFgHh4iJj5kLl6mMn7oPp8",
                    description = "The key. Shown ONLY in this response; we keep a hash. Store it as a secret.")
            String key,
            @Schema(example = "ibpos_k3Zr9Q") String keyPrefix,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789") UUID merchantId,
            @Schema(example = "null", nullable = true) UUID shopId,
            List<String> allowedIps,
            LocalDateTime createdAt) {}

    @Schema(name = "PosApiKey")
    public record KeyView(
            @Schema(example = "3c9d8e7f-1a2b-4c3d-8e9f-0a1b2c3d4e5f") UUID id,
            @Schema(example = "GAAP cloud - Chicken Inn") String name,
            @Schema(example = "ibpos_k3Zr9Q") String keyPrefix,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789") UUID merchantId,
            @Schema(nullable = true) UUID shopId,
            List<String> allowedIps,
            @Schema(example = "ACTIVE", allowableValues = {"ACTIVE", "REVOKED"}) String status,
            @Schema(example = "admin@innbucks.co.zw") String createdBy,
            LocalDateTime createdAt,
            @Schema(nullable = true) LocalDateTime lastUsedAt,
            @Schema(nullable = true) LocalDateTime revokedAt,
            @Schema(nullable = true) String revokedBy) {}

    @Schema(name = "PosApiKeyPage")
    public record KeyPage(
            List<KeyView> content,
            @Schema(example = "1") long totalElements,
            @Schema(example = "1") int totalPages,
            @Schema(example = "0", description = "Zero-based page index.") int number,
            @Schema(example = "20") int size) {}

    @Schema(name = "PosApiKeyRevokeRequest")
    public record RevokeRequest(
            @Size(max = 500, message = "note must not exceed 500 characters")
            @Schema(example = "Key rotated after GAAP's server migration.", nullable = true)
            String note) {}

    @Schema(name = "PosTokenRequest")
    public record TokenRequest(
            @Schema(example = "11111111-aaaa-bbbb-cccc-222222222222", nullable = true,
                    description = "The shop the token acts for. Required to act at one shop with a merchant-wide "
                            + "key; must match (or be omitted) for a shop-pinned key.")
            UUID shopId) {}

    @Schema(name = "PosTokenResponse")
    public record TokenResponse(
            @Schema(example = "eyJhbGciOiJIUzI1NiJ9...") String token,
            @Schema(example = "Bearer") String tokenType,
            @Schema(example = "900", description = "Seconds until the token expires. Ask for a new one before then.")
            long expiresIn,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789") UUID merchantId,
            @Schema(example = "11111111-aaaa-bbbb-cccc-222222222222", nullable = true) UUID shopId) {}
}
