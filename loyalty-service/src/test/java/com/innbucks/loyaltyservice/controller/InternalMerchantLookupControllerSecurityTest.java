package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for the service-to-service lookup endpoints. These don't use
 * JWT — they're gated by the X-Internal-Token shared secret. The JwtFilter
 * is configured to skip /loyalty/internal/** entirely, so the only gate is
 * the controller's own header check.
 */
class InternalMerchantLookupControllerSecurityTest extends ControllerSecurityTestBase {

    @Value("${innbucks.internal-api-token}") String internalToken;

    // ------------------------------------------------------------------
    // 401: header missing or wrong
    // ------------------------------------------------------------------

    @Test
    void by_admin_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/by-admin")
                        .param("email", "anyone@test.local"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void by_admin_with_wrong_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/by-admin")
                        .header("X-Internal-Token", "definitely-not-the-real-token")
                        .param("email", "anyone@test.local"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_shop_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/shops/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_shop_with_wrong_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/shops/{id}", UUID.randomUUID())
                        .header("X-Internal-Token", "still-not-the-real-token"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 404: correct token but the resource doesn't exist — proves the
    // header gate accepted the right token and we got through to lookup.
    // ------------------------------------------------------------------

    @Test
    void get_shop_with_correct_token_and_unknown_id_returns_404() throws Exception {
        mockMvc.perform(get("/loyalty/internal/shops/{id}", UUID.randomUUID())
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void by_admin_with_correct_token_and_unknown_email_returns_404() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/by-admin")
                        .header("X-Internal-Token", internalToken)
                        .param("email", "nobody-here@test.local"))
                .andExpect(status().isNotFound());
    }

    @Test
    void by_admin_with_correct_token_and_blank_email_returns_400() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/by-admin")
                        .header("X-Internal-Token", internalToken)
                        .param("email", ""))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // A JWT is irrelevant here — internal endpoints ignore Authorization
    // entirely. This test guards against accidentally adding @PreAuthorize.
    // ------------------------------------------------------------------

    @Test
    void jwt_is_ignored_on_internal_endpoints() throws Exception {
        String adminJwt = jwt("admin@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/internal/shops/{id}", UUID.randomUUID())
                        .header("Authorization", bearer(adminJwt)))
                .andExpect(status().isUnauthorized());
    }
}
