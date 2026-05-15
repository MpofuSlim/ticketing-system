package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.TransferService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security surface tests for TransactionController. This is the points ledger —
 * earn, redeem, adjust, reverse, transfer. Wrong role here = unauthorized
 * points movement, so we keep the assertions strict.
 */
class TransactionControllerSecurityTest extends ControllerSecurityTestBase {

    @MockitoBean TransactionService transactionService;
    @MockitoBean TransferService transferService;
    @Autowired LoyaltyUserRepository loyaltyUsers;

    // Bodies satisfy @Valid so requests reach the @PreAuthorize gate.
    private static final String VALID_TXN_BODY = """
            {"userId":"11111111-2222-3333-4444-555555555555","type":"PURCHASE","amount":100.00}
            """;
    private static final String VALID_REDEMPTION_BODY = """
            {"userId":"11111111-2222-3333-4444-555555555555","points":500.0}
            """;
    private static final String VALID_TRANSFER_BODY = """
            {"fromUserId":"11111111-2222-3333-4444-555555555555",
             "toUserId":"66666666-7777-8888-9999-000000000000","points":250.0}
            """;
    private static final String EMPTY = "{}";

    // ------------------------------------------------------------------
    // 401: no token
    // ------------------------------------------------------------------

    @Test
    void post_transaction_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TXN_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_redeem_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REDEMPTION_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_transfer_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TRANSFER_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 401: present-but-invalid token (covers JwtFilter fix)
    // ------------------------------------------------------------------

    @Test
    void post_transaction_with_malformed_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/transactions")
                        .header("Authorization", "Bearer not.a.real.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TXN_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 403: wrong role on admin-only endpoints
    // ------------------------------------------------------------------

    @Test
    void customer_cannot_post_transaction() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/transactions")
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TXN_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_reverse_transaction() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/transactions/{id}/reverse", UUID.randomUUID())
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_adjust_points() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/transactions/adjust")
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // 400: missing X-Tenant-Id on a tenant-scoped endpoint
    // ------------------------------------------------------------------

    @Test
    void admin_without_tenant_header_returns_400() throws Exception {
        String admin = jwt("admin@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(post("/loyalty/transactions")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TXN_BODY))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // 403: cross-tenant — admin not a member of the tenant they target
    // ------------------------------------------------------------------

    @Test
    void admin_who_is_not_member_of_tenant_returns_403() throws Exception {
        // /transfer is one of the endpoints that goes through tenantContext.requireTenant(),
        // which is where the membership check actually runs.
        UUID otherTenant = newTenant("txn-cross");
        String stranger = jwt("stranger@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/transfer")
                        .header("Authorization", bearer(stranger))
                        .header("X-Tenant-Id", otherTenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TRANSFER_BODY))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // GET /users/{id}/transactions — IDOR / cross-customer leak guards.
    // Verifies the gates added when closing the audit's HIGH finding:
    //   1. tenant header required
    //   2. target user must belong to that tenant
    //   3. caller must own the target unless they're an admin role
    // ------------------------------------------------------------------

    @Test
    void recent_without_tenant_header_returns_400() throws Exception {
        String customerToken = TestJwtFactory.builder("alice@test.local")
                .role("CUSTOMER").phoneNumber("+263770070100").sign(jwtSecret);
        mockMvc.perform(get("/loyalty/users/{id}/transactions", UUID.randomUUID())
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recent_customer_reading_someone_elses_history_returns_403() throws Exception {
        // Tenant + two LoyaltyUsers (Alice + Bob). Alice's JWT tries to read
        // Bob's transaction history — this is the actual IDOR attack the fix
        // is preventing. Expected: 403 NOT_WALLET_OWNER.
        UUID tenant = newTenant("txn-leak");
        joinTenant(tenant, "alice@test.local");

        LoyaltyUser alice = new LoyaltyUser();
        alice.setTenantId(tenant);
        alice.setPhoneNumber("+263770070100");
        loyaltyUsers.save(alice);

        LoyaltyUser bob = new LoyaltyUser();
        bob.setTenantId(tenant);
        bob.setPhoneNumber("+263770070200");
        loyaltyUsers.save(bob);

        String aliceToken = TestJwtFactory.builder("alice@test.local")
                .role("CUSTOMER").phoneNumber(alice.getPhoneNumber()).sign(jwtSecret);

        mockMvc.perform(get("/loyalty/users/{id}/transactions", bob.getId())
                        .header("Authorization", bearer(aliceToken))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void recent_customer_reading_own_history_returns_200() throws Exception {
        // Control case: same caller, same target, must succeed.
        UUID tenant = newTenant("txn-self");
        joinTenant(tenant, "alice@test.local");

        LoyaltyUser alice = new LoyaltyUser();
        alice.setTenantId(tenant);
        alice.setPhoneNumber("+263770070300");
        loyaltyUsers.save(alice);

        // The mocked TransactionService needs to return an empty Page so the
        // controller's PageResponse.from call doesn't NPE.
        Page<com.innbucks.loyaltyservice.dto.Dtos.TransactionResponse> empty =
                new PageImpl<>(List.of(), Pageable.unpaged(), 0);
        when(transactionService.recentForUser(any(UUID.class), any(Pageable.class))).thenReturn(empty);

        String aliceToken = TestJwtFactory.builder("alice@test.local")
                .role("CUSTOMER").phoneNumber(alice.getPhoneNumber()).sign(jwtSecret);

        mockMvc.perform(get("/loyalty/users/{id}/transactions", alice.getId())
                        .header("Authorization", bearer(aliceToken))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void recent_merchant_admin_reading_any_user_in_tenant_returns_200() throws Exception {
        // Admin bypass: MERCHANT_ADMIN can inspect any user's history for ops
        // / customer-support. Bob's data is fair game for the merchant admin
        // who oversees Bob's merchant.
        UUID tenant = newTenant("txn-admin");
        joinTenant(tenant, "ops@test.local");

        LoyaltyUser bob = new LoyaltyUser();
        bob.setTenantId(tenant);
        bob.setPhoneNumber("+263770070400");
        loyaltyUsers.save(bob);

        Page<com.innbucks.loyaltyservice.dto.Dtos.TransactionResponse> empty =
                new PageImpl<>(List.of(), Pageable.unpaged(), 0);
        when(transactionService.recentForUser(any(UUID.class), any(Pageable.class))).thenReturn(empty);

        String adminToken = jwt("ops@test.local", "MERCHANT_ADMIN");

        mockMvc.perform(get("/loyalty/users/{id}/transactions", bob.getId())
                        .header("Authorization", bearer(adminToken))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk());
    }
}
