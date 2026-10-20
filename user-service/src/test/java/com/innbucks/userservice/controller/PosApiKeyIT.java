package com.innbucks.userservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.entity.PosApiKey;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.PosApiKeyRepository;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.security.TokenVersionPublisher;
import com.innbucks.userservice.security.WithSuperAdmin;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS API keys end to end against real Postgres: issue (SUPER_ADMIN only), the
 * key-for-token exchange with its allow-list and shop rules, the minted
 * token's shape, and revocation reaching the live tokens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PosApiKeyIT {

    private static final String GAAP_IP = "196.27.104.10";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired PosApiKeyRepository keys;
    @Autowired JwtUtil jwtUtil;

    @MockitoBean LoyaltyServiceClient loyalty;
    @MockitoBean TokenVersionPublisher tokenVersionPublisher;

    private final UUID merchantId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();

    @Test
    @WithSuperAdmin
    void aStoreKey_mintsACashierTokenForThatStore() throws Exception {
        shopBelongsTo(shopId, merchantId);
        JsonNode created = issue("""
                {"name":"GAAP - Chicken Inn Avondale","shopId":"%s","allowedIps":["%s"]}
                """.formatted(shopId, GAAP_IP))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.data.shopId").value(shopId.toString()))
                .andReturn().getResponse().getContentAsString().transform(this::data);
        String key = created.get("key").asText();
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(key).startsWith("ibpos_").hasSizeGreaterThan(40);
        assertThat(created.get("keyPrefix").asText()).isEqualTo(key.substring(0, 12));

        // Only the hash is kept.
        PosApiKey row = keys.findById(id).orElseThrow();
        assertThat(row.getKeyHash()).hasSize(64).isNotEqualTo(key);
        assertThat(row.getKeyHash()).doesNotContain(key);

        String token = data(exchange(key, GAAP_IP, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andExpect(jsonPath("$.data.shopId").value(shopId.toString()))
                .andReturn().getResponse().getContentAsString()).get("token").asText();

        Claims claims = jwtUtil.parseClaims(token);
        assertThat(jwtUtil.extractRoles(claims)).containsExactly("SHOP_USER");
        assertThat(claims.get("perms", List.class)).isEmpty();
        assertThat(claims.get("merchantId", String.class)).isEqualTo(merchantId.toString());
        assertThat(claims.get("shopId", String.class)).isEqualTo(shopId.toString());
        assertThat(claims.get("userUuid", String.class)).isEqualTo(id.toString());
        assertThat(claims.get("homeCountry", String.class)).isEqualTo("ZW");
        assertThat(claims.get("tokenVersion", Long.class)).isZero();
        assertThat(claims.get("phoneNumber")).isNull();
        assertThat(claims.get("orgId")).isNull();
        assertThat(claims.get("mustChangePassword")).isNull();
        assertThat(keys.findById(id).orElseThrow().getLastUsedAt()).isNotNull();

        // The token is not a user-service session: there is no such account.
        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithSuperAdmin
    void aStoreKey_refusesAnotherStore() throws Exception {
        shopBelongsTo(shopId, merchantId);
        String key = data(issue("{\"name\":\"store\",\"shopId\":\"%s\"}".formatted(shopId))
                .andReturn().getResponse().getContentAsString()).get("key").asText();
        exchange(key, GAAP_IP, UUID.randomUUID())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("pos_shop_not_allowed"));
        exchange(key, GAAP_IP, shopId).andExpect(status().isOk());
    }

    @Test
    @WithSuperAdmin
    void aMerchantKey_actsForItsMerchantsShopsOnly() throws Exception {
        UUID otherShop = UUID.randomUUID();
        shopBelongsTo(shopId, merchantId);
        shopBelongsTo(otherShop, UUID.randomUUID());
        String key = data(issue("{\"name\":\"merchant\",\"merchantId\":\"%s\"}".formatted(merchantId))
                .andExpect(jsonPath("$.data.shopId").doesNotExist())
                .andReturn().getResponse().getContentAsString()).get("key").asText();

        exchange(key, GAAP_IP, shopId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").value(shopId.toString()));
        exchange(key, GAAP_IP, otherShop)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("pos_shop_not_allowed"));
        // A shop loyalty can't confirm is refused, never assumed.
        UUID unknown = UUID.randomUUID();
        when(loyalty.findShop(unknown)).thenReturn(Optional.empty());
        exchange(key, GAAP_IP, unknown).andExpect(status().isForbidden());
        // No shop named: a merchant-wide token.
        exchange(key, GAAP_IP, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").doesNotExist());
    }

    @Test
    @WithSuperAdmin
    void theAllowList_andUnknownKeys_areOneOpaque401() throws Exception {
        String key = data(issue("""
                {"name":"ip","merchantId":"%s","allowedIps":["41.190.32.0/24"]}
                """.formatted(merchantId)).andReturn().getResponse().getContentAsString()).get("key").asText();

        exchange(key, "41.190.32.77", null).andExpect(status().isOk());
        for (String ip : new String[] {"41.190.33.1", null}) {
            exchange(key, ip, null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("API key rejected."))
                    .andExpect(jsonPath("$.data.errorCode").value("pos_key_rejected"));
        }
        exchange("ibpos_" + "x".repeat(43), "41.190.32.77", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("pos_key_rejected"));
        mockMvc.perform(post("/auth/pos/token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("pos_key_rejected"));
    }

    @Test
    @WithSuperAdmin
    void revoking_refusesTheKey_andMovesItsTokensOn() throws Exception {
        JsonNode created = data(issue("{\"name\":\"rotate me\",\"merchantId\":\"%s\"}".formatted(merchantId))
                .andReturn().getResponse().getContentAsString());
        String key = created.get("key").asText();
        UUID id = UUID.fromString(created.get("id").asText());
        exchange(key, GAAP_IP, null).andExpect(status().isOk());

        mockMvc.perform(post("/admin/pos-keys/{id}/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"rotated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.revokedBy").value("admin@innbucks.co.zw"));
        // The live tokens die through the shared version key loyalty checks.
        verify(tokenVersionPublisher).publishAfterCommit(id, 1L);

        exchange(key, GAAP_IP, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("pos_key_rejected"));
        mockMvc.perform(post("/admin/pos-keys/{id}/revoke", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("pos_key_already_revoked"));
        mockMvc.perform(post("/admin/pos-keys/{id}/revoke", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("pos_key_not_found"));

        mockMvc.perform(get("/admin/pos-keys").param("merchantId", merchantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data.content[0].key").doesNotExist());
    }

    @Test
    @WithSuperAdmin
    void creationIsValidated() throws Exception {
        issue("{\"name\":\"nothing\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("pos_key_merchant_required"));
        UUID ghost = UUID.randomUUID();
        when(loyalty.findShop(ghost)).thenReturn(Optional.empty());
        issue("{\"name\":\"ghost\",\"shopId\":\"%s\"}".formatted(ghost))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("pos_key_shop_unknown"));
        shopBelongsTo(shopId, merchantId);
        issue("{\"name\":\"mismatch\",\"shopId\":\"%s\",\"merchantId\":\"%s\"}".formatted(shopId, UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("pos_key_merchant_mismatch"));
        issue("{\"name\":\"dns\",\"merchantId\":\"%s\",\"allowedIps\":[\"gaap.example.com\"]}".formatted(merchantId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("pos_key_bad_ip"));
        issue("{\"merchantId\":\"%s\"}".formatted(merchantId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.name").value("name is required"));
    }

    @Test
    @WithMockUser(username = "ops@innbucks.co.zw", authorities = {"pos-keys:read", "users:read"})
    void issuingNeedsTheReservedPermission() throws Exception {
        issue("{\"name\":\"nope\",\"merchantId\":\"%s\"}".formatted(merchantId))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/pos-keys/{id}/revoke", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/pos-keys")).andExpect(status().isOk());
        verify(tokenVersionPublisher, never()).publishAfterCommit(any(), anyLong());
    }

    // ------------------------------------------------------------------

    private void shopBelongsTo(UUID shop, UUID merchant) {
        when(loyalty.findShop(shop)).thenReturn(Optional.of(new LoyaltyServiceClient.ShopLookupResponse(
                shop.toString(), merchant.toString(), UUID.randomUUID().toString(), "ACTIVE")));
    }

    private ResultActions issue(String body) throws Exception {
        return mockMvc.perform(post("/admin/pos-keys").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions exchange(String key, String ip, UUID shop) throws Exception {
        var req = post("/auth/pos/token").header("X-Api-Key", key);
        if (ip != null) req = req.header("X-Real-IP", ip);
        if (shop != null) req = req.contentType(MediaType.APPLICATION_JSON).content("{\"shopId\":\"%s\"}".formatted(shop));
        return mockMvc.perform(req);
    }

    private JsonNode data(String body) {
        try {
            return json.readTree(body).get("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
