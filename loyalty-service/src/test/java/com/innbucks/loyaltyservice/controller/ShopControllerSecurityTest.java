package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.service.ShopService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security surface tests for ShopController. Shops are the per-outlet bucket
 * staff get scoped to via SHOP_ADMIN / SHOP_USER roles, so the role + tenant
 * boundaries here decide who can onboard which staff in user-service later.
 */
class ShopControllerSecurityTest extends ControllerSecurityTestBase {

    @MockitoBean ShopService shopService;

    private static final String VALID_SHOP_BODY = """
            {"merchantId":"b4c0d2e3-2345-6789-abcd-ef0123456789","name":"Avondale"}
            """;

    @Test
    void post_shop_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHOP_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_shops_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/shops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_shop_by_id_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/shops/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_shops_with_malformed_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/shops")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customer_cannot_create_shop() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/shops")
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHOP_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_update_shop() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(put("/loyalty/shops/{id}", UUID.randomUUID())
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHOP_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_activate_shop() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/shops/{id}/activate", UUID.randomUUID())
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_without_tenant_header_returns_400() throws Exception {
        String admin = jwt("admin@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/shops")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void admin_who_is_not_member_of_tenant_returns_403() throws Exception {
        UUID otherTenant = newTenant("shop-cross");
        String stranger = jwt("stranger@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/shops")
                        .header("Authorization", bearer(stranger))
                        .header("X-Tenant-Id", otherTenant.toString()))
                .andExpect(status().isForbidden());
    }
}
