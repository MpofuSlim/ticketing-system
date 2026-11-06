package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.SalesAgentInviteDTOs;
import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.exception.SalesAgentInviteException;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.OtpService;
import com.innbucks.userservice.service.SalesAgentInviteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sales-agent invite HTTP surface (V56), through real dispatch and the real
 * {@link GlobalExceptionHandler}: the public {@code /auth/sales-agent-invite/**}
 * calls and the internal {@code /users/internal/sales-agent-invites} ones. Every
 * refusal is asserted with its specific status, never a 4xx family.
 */
class SalesAgentInviteControllerTest {

    private static final String INTERNAL_TOKEN = "the-shared-secret";
    private static final String TOKEN = "SAI-dGhpcy1pcy1ub3QtYS1yZWFsLWludml0ZS10b2tlbgA";
    private static final String TEAM_MEMBER = "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e";

    private SalesAgentInviteService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SalesAgentInviteService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new SalesAgentInviteController(service),
                        new InternalSalesAgentInviteController(
                                new InternalTokenAuthorizer(INTERNAL_TOKEN, mock(AuditService.class)), service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("internal create: no or a wrong token is 401 before the body is read")
    void internal_create_needsTheToken() throws Exception {
        mvc.perform(post("/users/internal/sales-agent-invites").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamMemberId\":\"not-a-uuid\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/users/internal/sales-agent-invites").header("X-Internal-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/users/internal/sales-agent-invites/{id}", "not-a-uuid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("internal create: 202 with expiresAt, in the ApiResult envelope")
    void internal_create_accepted() throws Exception {
        when(service.create(any())).thenReturn(
                new SalesAgentInviteDTOs.Created(LocalDateTime.of(2026, 11, 9, 10, 0)));

        mvc.perform(post("/users/internal/sales-agent-invites").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamMemberId":"%s","organizerUuid":"8f14e45f-ceea-467f-a2b7-1c2d3e4f5a6b",
                                 "organizerName":"Harare Arena Events","phoneNumber":"+263771234567",
                                 "email":"tendai.moyo@gmail.com"}
                                """.formatted(TEAM_MEMBER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("202 ACCEPTED"))
                .andExpect(jsonPath("$.message").value("Portal invite sent"))
                .andExpect(jsonPath("$.data.expiresAt").exists());

        ArgumentCaptor<SalesAgentInviteDTOs.CreateRequest> body =
                ArgumentCaptor.forClass(SalesAgentInviteDTOs.CreateRequest.class);
        verify(service).create(body.capture());
        assertThat(body.getValue().teamMemberId()).isEqualTo(TEAM_MEMBER);
        assertThat(body.getValue().email()).isEqualTo("tendai.moyo@gmail.com");
        assertThat(body.getValue().toString()).doesNotContain("tendai").doesNotContain("771234567");
    }

    @Test
    @DisplayName("internal create: the service's refusals keep their status and errorCode")
    void internal_create_refusals() throws Exception {
        doThrow(SalesAgentInviteException.invalidEmail()).when(service).create(any());
        mvc.perform(post("/users/internal/sales-agent-invites").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400 BAD_REQUEST"))
                .andExpect(jsonPath("$.data.errorCode").value("invalid_email"));

        doThrow(StaffPolicyException.emailDomainReserved()).when(service).create(any());
        mvc.perform(post("/users/internal/sales-agent-invites").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("email_domain_reserved"));

        doThrow(SalesAgentInviteException.unconfigured()).when(service).create(any());
        mvc.perform(post("/users/internal/sales-agent-invites").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("sales_agent_invites_unconfigured"));
    }

    @Test
    @DisplayName("internal revoke: 200 with the count")
    void internal_revoke() throws Exception {
        when(service.revoke(TEAM_MEMBER)).thenReturn(new SalesAgentInviteDTOs.Revoked(1));
        mvc.perform(delete("/users/internal/sales-agent-invites/{id}", TEAM_MEMBER)
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200 OK"))
                .andExpect(jsonPath("$.data.revoked").value(1));
    }

    // ------------------------------------------------------------------
    // Public
    // ------------------------------------------------------------------

    @Test
    @DisplayName("inspect: 200 no-store with the invite's details; a missing token is a 400 naming the field")
    void inspect() throws Exception {
        when(service.inspect(TOKEN)).thenReturn(new SalesAgentInviteDTOs.Inspection("Harare Arena Events",
                "tendai.moyo@gmail.com", "****4567", LocalDateTime.of(2026, 11, 9, 10, 0)));

        mvc.perform(post("/auth/sales-agent-invite/inspect").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("Invite is valid"))
                .andExpect(jsonPath("$.data.organizerName").value("Harare Arena Events"))
                .andExpect(jsonPath("$.data.email").value("tendai.moyo@gmail.com"))
                .andExpect(jsonPath("$.data.maskedPhone").value("****4567"))
                .andExpect(jsonPath("$.data.expiresAt").exists());

        mvc.perform(post("/auth/sales-agent-invite/inspect").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.token").value("token is required"));
    }

    @Test
    @DisplayName("an invalid link is one 400 invite_invalid, never a 401")
    void inviteInvalid() throws Exception {
        when(service.inspect(anyString())).thenThrow(SalesAgentInviteException.inviteInvalid());
        mvc.perform(post("/auth/sales-agent-invite/inspect").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"SAI-nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("This invite link is no longer valid. Ask the organiser to send a new one."))
                .andExpect(jsonPath("$.data.errorCode").value("invite_invalid"));
    }

    @Test
    @DisplayName("phone-code: 200 generic; the code quota is a 429; a refusal keeps its 409")
    void phoneCode() throws Exception {
        mvc.perform(post("/auth/sales-agent-invite/phone-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("We sent a code to the phone number on this invite."))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(service).sendPhoneCode(TOKEN);

        doThrow(new OtpService.OtpRateLimitException("Too many OTP requests. Try again in 30 minute(s)."))
                .when(service).sendPhoneCode(TOKEN);
        mvc.perform(post("/auth/sales-agent-invite/phone-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many OTP requests. Try again in 30 minute(s)."));

        doThrow(SalesAgentInviteException.accountNotEligible()).when(service).sendPhoneCode(TOKEN);
        mvc.perform(post("/auth/sales-agent-invite/phone-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("account_not_eligible"));
    }

    @Test
    @DisplayName("accept: 200 {email, next: SIGN_IN}; the client IP is X-Real-IP")
    void accept() throws Exception {
        when(service.accept(any(), any())).thenReturn(
                new SalesAgentInviteDTOs.Accepted("tendai.moyo@gmail.com", "SIGN_IN"));

        mvc.perform(post("/auth/sales-agent-invite/accept").header("X-Real-IP", "198.51.100.4")
                        .header("X-Forwarded-For", "6.6.6.6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"phoneCode\":\"482913\","
                                + "\"password\":\"Correct-Horse-7-Battery\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value(SalesAgentInviteService.ACCEPTED_MESSAGE))
                .andExpect(jsonPath("$.data.email").value("tendai.moyo@gmail.com"))
                .andExpect(jsonPath("$.data.next").value("SIGN_IN"));

        ArgumentCaptor<AuditContext> context = ArgumentCaptor.forClass(AuditContext.class);
        ArgumentCaptor<SalesAgentInviteDTOs.AcceptRequest> body =
                ArgumentCaptor.forClass(SalesAgentInviteDTOs.AcceptRequest.class);
        verify(service).accept(body.capture(), context.capture());
        assertThat(context.getValue().ipAddress()).isEqualTo("198.51.100.4");
        assertThat(body.getValue().toString()).doesNotContain(TOKEN).doesNotContain("Correct-Horse");
    }

    @Test
    @DisplayName("accept: a short password or a missing code is a 400 naming the field, before the service")
    void accept_validation() throws Exception {
        mvc.perform(post("/auth/sales-agent-invite/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"phoneCode\":\"482913\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.password").value("Password must be between 8 and 72 characters"));
        mvc.perform(post("/auth/sales-agent-invite/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"password\":\"Correct-Horse-7-Battery\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.phoneCode").value("phoneCode is required"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("accept: each refusal keeps its status and errorCode")
    void accept_refusals() throws Exception {
        String body = "{\"token\":\"" + TOKEN + "\",\"phoneCode\":\"000000\",\"password\":\"Correct-Horse-7-Battery\"}";
        Object[][] cases = {
                {SalesAgentInviteException.phoneCodeInvalid(), 400, "phone_code_invalid"},
                {SalesAgentInviteException.emailMismatch(), 409, "email_mismatch"},
                {SalesAgentInviteException.emailInUse(), 409, "email_in_use"},
                {SalesAgentInviteException.accountNotEligible(), 409, "account_not_eligible"},
                {SalesAgentInviteException.tryAgain(), 409, "try_again"},
                {StaffPolicyException.emailDomainReserved(), 400, "email_domain_reserved"},
        };
        for (Object[] c : cases) {
            doThrow((RuntimeException) c[0]).when(service).accept(any(), any());
            mvc.perform(post("/auth/sales-agent-invite/accept").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().is((Integer) c[1]))
                    .andExpect(jsonPath("$.data.errorCode").value((String) c[2]));
        }
    }
}
