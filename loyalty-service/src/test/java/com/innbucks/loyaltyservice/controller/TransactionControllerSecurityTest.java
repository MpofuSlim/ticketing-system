package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.TransferService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

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
        // which is where the membership check actually runs. /users/{id}/transactions
        // doesn't take a tenant header — that's tracked as a separate audit finding.
        UUID otherTenant = newTenant("txn-cross");
        String stranger = jwt("stranger@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/transfer")
                        .header("Authorization", bearer(stranger))
                        .header("X-Tenant-Id", otherTenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TRANSFER_BODY))
                .andExpect(status().isForbidden());
    }
}
