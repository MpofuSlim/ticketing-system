package com.innbucks.userservice.controller;

import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.exception.SupportPolicyException;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.support.ConsoleSupportActions;
import com.innbucks.userservice.support.SupportAgent;
import com.innbucks.userservice.support.SupportAgentResolver;
import com.innbucks.userservice.support.SupportSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The support controller through real MVC dispatch with the real
 * {@link GlobalExceptionHandler}: every refusal renders as the documented
 * envelope with its errorCode, a 429 carries {@code Retry-After} and
 * {@code data.retryAfterSeconds}, and nothing support sends is cacheable.
 */
class AdminSupportDispatchTest {

    private SupportSearchService search;
    private ConsoleSupportActions actions;
    private SupportAgentResolver agents;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        search = mock(SupportSearchService.class);
        actions = mock(ConsoleSupportActions.class);
        agents = mock(SupportAgentResolver.class);
        when(agents.require(any())).thenReturn(new SupportAgent("agent.one@innbucks.co.zw", 7L, UUID.randomUUID(),
                "agent.one@innbucks.co.zw", null, null, Set.of("support-console:read")));
        mvc = MockMvcBuilders.standaloneSetup(new AdminSupportController(search, actions, agents))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken("agent.one@innbucks.co.zw", null, List.of());
    }

    @Test
    @DisplayName("a 429 carries Retry-After, data.retryAfterSeconds and no-store")
    void rateLimited() throws Exception {
        when(search.search(any(), anyString(), any())).thenThrow(SupportPolicyException.rateLimited(212, "10m"));
        mvc.perform(post("/admin/support/customers/search").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"+263771234567\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "212"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("429 TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.message").value("You've looked up a lot of customers in a short time. Try again in 4 minutes."))
                .andExpect(jsonPath("$.data.errorCode").value("lookup_rate_limited"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(212))
                .andExpect(jsonPath("$.data.window").value("10m"));
    }

    @Test
    @DisplayName("a refused query renders its errorCode — 400 query_not_accepted")
    void refusedQuery() throws Exception {
        when(search.search(any(), anyString(), any())).thenThrow(SupportPolicyException.queryNotAccepted());
        mvc.perform(post("/admin/support/customers/search").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"4111111111111111\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("query_not_accepted"))
                .andExpect(jsonPath("$.message").value(SupportPolicyException.QUERY_NOT_ACCEPTED_MESSAGE));
    }

    @Test
    @DisplayName("a blank q is the ordinary validation 400, and the search never runs")
    void blankQuery() throws Exception {
        mvc.perform(post("/admin/support/customers/search").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.q").value("q is required"));
        verifyNoInteractions(search);
    }

    @Test
    @DisplayName("a write with no note is the validation 400; with one, the header and body reach the service")
    void writePlumbing() throws Exception {
        mvc.perform(post("/admin/support/console-users/1042/unlock").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lookupId\":\"SLK-7Q2M9X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.note").value("note is required"));
        verifyNoInteractions(actions);

        mvc.perform(post("/admin/support/console-users/1042/unlock").principal(auth())
                        .header("Idempotency-Key", "3f6c1a52-7b0e-4d8a-9c21-5e4f7a8b9c0d")
                        .header("X-Forwarded-For", "41.221.147.12, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lookupId\":\"SLK-7Q2M9X\",\"note\":\"verified\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        ArgumentCaptor<AuditContext> ctx = ArgumentCaptor.forClass(AuditContext.class);
        verify(actions).execute(eq(ConsoleSupportActions.Op.UNLOCK), any(), eq(1042L), any(),
                eq("3f6c1a52-7b0e-4d8a-9c21-5e4f7a8b9c0d"), ctx.capture());
        org.assertj.core.api.Assertions.assertThat(ctx.getValue().ipAddress()).isEqualTo("41.221.147.12");
    }

    @Test
    @DisplayName("binding refusals render as documented: 400 lookup_required, 409 lookup_expired, 404 target_not_found")
    void bindingRefusals() throws Exception {
        when(actions.detail(any(), eq(1042L), isNull(), any())).thenThrow(SupportPolicyException.lookupRequired());
        mvc.perform(get("/admin/support/console-users/1042").principal(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("lookup_required"));

        when(actions.detail(any(), eq(1042L), eq("SLK-OLD000"), any())).thenThrow(SupportPolicyException.lookupExpired());
        mvc.perform(get("/admin/support/console-users/1042").param("lookupId", "SLK-OLD000").principal(auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Search for the customer again."))
                .andExpect(jsonPath("$.data.errorCode").value("lookup_expired"));

        when(actions.detail(any(), eq(9999L), eq("SLK-7Q2M9X"), any())).thenThrow(SupportPolicyException.targetNotFound());
        mvc.perform(get("/admin/support/console-users/9999").param("lookupId", "SLK-7Q2M9X").principal(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_found"));
    }

    @Test
    @DisplayName("self-action and staff-target refusals are specific 403s")
    void aimedRefusals() throws Exception {
        when(actions.execute(eq(ConsoleSupportActions.Op.MFA_RESET), any(), eq(1042L), any(), any(), any()))
                .thenThrow(SupportPolicyException.selfAction());
        mvc.perform(post("/admin/support/console-users/1042/mfa/reset").principal(auth())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lookupId\":\"SLK-7Q2M9X\",\"note\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("support_self_action"))
                .andExpect(jsonPath("$.message").value("You can't act on your own account. Ask a colleague."));
    }

    private org.springframework.test.web.servlet.ResultActions write(String action) throws Exception {
        return mvc.perform(post("/admin/support/console-users/1042/" + action).principal(auth())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lookupId\":\"SLK-7Q2M9X\",\"note\":\"verified\"}"));
    }

    @Test
    @DisplayName("T7: an agent whose account can't be resolved is 403 agent_not_resolved — nothing runs")
    void agentNotResolved() throws Exception {
        when(agents.require(any())).thenThrow(SupportPolicyException.agentNotResolved());
        mvc.perform(post("/admin/support/customers/search").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"+263771234567\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("agent_not_resolved"))
                .andExpect(jsonPath("$.message").value("Your account couldn't be verified. Sign in again."));
        write("unlock").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("agent_not_resolved"));
        verifyNoInteractions(search, actions);
    }

    @Test
    @DisplayName("T7: support switched off is 404 support_disabled; an unrecordable read is 503 support_log_unavailable")
    void disabledAndLogUnavailable() throws Exception {
        when(search.search(any(), anyString(), any())).thenThrow(SupportPolicyException.supportDisabled());
        mvc.perform(post("/admin/support/customers/search").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"+263771234567\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404 NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Customer support isn't available on this server."))
                .andExpect(jsonPath("$.data.errorCode").value("support_disabled"));

        when(actions.detail(any(), eq(1042L), eq("SLK-7Q2M9X"), any())).thenThrow(SupportPolicyException.logUnavailable());
        mvc.perform(get("/admin/support/console-users/1042").param("lookupId", "SLK-7Q2M9X").principal(auth()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("503 SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("We couldn't record this lookup, so it wasn't shown. Try again."))
                .andExpect(jsonPath("$.data.errorCode").value("support_log_unavailable"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("C4: a search whose records can't be read is 503 support_search_unavailable")
    void searchUnavailable() throws Exception {
        when(search.search(any(), anyString(), any())).thenThrow(SupportPolicyException.searchUnavailable());
        mvc.perform(post("/admin/support/customers/search").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"+263771234567\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("We couldn't search the customer records just now. Try again in a minute."))
                .andExpect(jsonPath("$.data.errorCode").value("support_search_unavailable"));
    }

    @Test
    @DisplayName("T7: 409 mfa_not_enrolled and 409 account_inactive render as documented; S3: 400 note_required")
    void actionRefusals() throws Exception {
        when(actions.execute(eq(ConsoleSupportActions.Op.MFA_RESET), any(), eq(1042L), any(), any(), any()))
                .thenThrow(SupportPolicyException.mfaNotEnrolled());
        write("mfa/reset").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409 CONFLICT"))
                .andExpect(jsonPath("$.message").value("This account hasn't set up two-factor sign-in, so there is nothing to reset."))
                .andExpect(jsonPath("$.data.errorCode").value("mfa_not_enrolled"));

        when(actions.execute(eq(ConsoleSupportActions.Op.UNLOCK), any(), eq(1042L), any(), any(), any()))
                .thenThrow(SupportPolicyException.accountInactive());
        write("unlock").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This account isn't active, so support can't change it. An "
                        + "administrator decides whether to approve or reactivate it."))
                .andExpect(jsonPath("$.data.errorCode").value("account_inactive"));

        when(actions.execute(eq(ConsoleSupportActions.Op.SEND_PASSWORD_RESET), any(), eq(1042L), any(), any(), any()))
                .thenThrow(SupportPolicyException.noteRequired());
        write("send-password-reset").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("note_required"))
                .andExpect(jsonPath("$.data.field").value("note"));
    }

    @Test
    @DisplayName("S3: a note over 500 characters is the validation 400 — the bound the seal keeps")
    void noteOver500IsRefused() throws Exception {
        String note = "x".repeat(501);
        mvc.perform(post("/admin/support/console-users/1042/unlock").principal(auth())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lookupId\":\"SLK-7Q2M9X\",\"note\":\"" + note + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.note").value("note must be 500 characters or fewer"));
        verifyNoInteractions(actions);
    }

    @Test
    @DisplayName("a lookupId over 16 or a caseId over 64 characters is the validation 400 the Swagger documents")
    void lookupIdAndCaseIdBounds() throws Exception {
        mvc.perform(post("/admin/support/console-users/1042/unlock").principal(auth())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lookupId\":\"" + "S".repeat(17) + "\",\"note\":\"verified\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.lookupId").value("lookupId must be 16 characters or fewer"));
        mvc.perform(post("/admin/support/console-users/1042/unlock").principal(auth())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lookupId\":\"SLK-7Q2M9X\",\"note\":\"verified\",\"caseId\":\"" + "c".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.caseId").value("caseId must be 64 characters or fewer"));
        verifyNoInteractions(actions);
    }

    @Test
    @DisplayName("a lookup without the console section is 409 lookup_expired with data.reason section_not_in_lookup")
    void lookupMissingSection() throws Exception {
        when(actions.detail(any(), eq(1042L), eq("SLK-3H8KD2"), any()))
                .thenThrow(SupportPolicyException.lookupMissingSection());
        mvc.perform(get("/admin/support/console-users/1042").param("lookupId", "SLK-3H8KD2").principal(auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Search for the customer again."))
                .andExpect(jsonPath("$.data.errorCode").value("lookup_expired"))
                .andExpect(jsonPath("$.data.reason").value("section_not_in_lookup"));
    }

    @Test
    @DisplayName("429 reset_code_limited carries no Retry-After and no data.retryAfterSeconds, as documented")
    void resetCodeLimitedHasNoRetryAfter() throws Exception {
        when(actions.execute(eq(ConsoleSupportActions.Op.SEND_PASSWORD_RESET), any(), eq(1042L), any(), any(), any()))
                .thenThrow(SupportPolicyException.resetCodeLimited());
        write("send-password-reset").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.data.errorCode").value("reset_code_limited"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").doesNotExist())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("503 audit_unavailable from a support write is no-store, like every other support refusal")
    void auditUnavailableIsNoStore() throws Exception {
        when(actions.execute(eq(ConsoleSupportActions.Op.UNLOCK), any(), eq(1042L), any(), any(), any()))
                .thenThrow(new com.innbucks.userservice.exception.AuditUnavailableException(null));
        write("unlock").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("We couldn't record this change, so it wasn't made. Try again."))
                .andExpect(jsonPath("$.data.errorCode").value("audit_unavailable"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
