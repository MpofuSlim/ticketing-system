package com.innbucks.userservice.controller;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.exception.AccountInactiveException;
import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.security.LoyaltySessionTokenIssuer;
import com.innbucks.userservice.security.MfaPolicy;
import com.innbucks.userservice.security.MfaTokenService;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.AuthService;
import com.innbucks.userservice.service.CustomerService;
import com.innbucks.userservice.service.FederatedLoginService;
import com.innbucks.userservice.service.LoginRateLimiter;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.service.OtpService;
import com.innbucks.userservice.service.PasswordResetService;
import com.innbucks.userservice.service.TokenRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A deactivated account's refresh, organization switch and MFA step each reach
 * the client as {@code 401 {"errorCode": "account_inactive"}} — through real
 * dispatch, because the defect this guards against is the handler Spring
 * picks: without its own {@code @ExceptionHandler} the refusal would fall into
 * the {@code RuntimeException} catch-all and become a vague 400 inviting a
 * retry that can never succeed.
 */
class AccountInactiveDispatchTest {

    private AuthService authService;
    private MfaTokenService mfaTokenService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mfaTokenService = mock(MfaTokenService.class);
        AuthController controller = new AuthController(
                authService, mock(CustomerService.class), mock(TokenRevocationService.class),
                mock(OtpService.class), mock(CellAffinityChecker.class), mock(JwtUtil.class),
                mock(LoyaltySessionTokenIssuer.class), mock(LoginRateLimiter.class), mock(AuditService.class),
                mock(PasswordResetService.class), mock(MfaService.class), mfaTokenService,
                mock(MfaPolicy.class), mock(UserRepository.class), mock(FederatedLoginService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static void assertAccountInactive(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("401 UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(AccountInactiveException.MESSAGE))
                .andExpect(jsonPath("$.data.errorCode").value("account_inactive"));
    }

    @Test
    @DisplayName("/auth/refresh for a deactivated account is 401 account_inactive")
    void refresh() throws Exception {
        when(authService.refresh(eq("refresh-token"), any(), any(AuditContext.class)))
                .thenThrow(new AccountInactiveException());

        assertAccountInactive(mvc.perform(post("/auth/refresh")
                .header("Authorization", "Bearer refresh-token")
                .header("X-Device-Id", "portal-browser-1")));
    }

    @Test
    @DisplayName("/auth/organization-context for a deactivated account is 401 account_inactive")
    void organizationContext() throws Exception {
        UUID org = UUID.randomUUID();
        when(authService.switchOrganization(eq("refresh-token"), any(), eq(org), any(AuditContext.class)))
                .thenThrow(new AccountInactiveException());

        assertAccountInactive(mvc.perform(post("/auth/organization-context")
                .header("Authorization", "Bearer refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"organizationId\":\"" + org + "\"}")));
    }

    @Test
    @DisplayName("/auth/login/mfa after a deactivation is 401 account_inactive")
    void loginMfa() throws Exception {
        when(authService.completeLoginWithMfa(eq("mfa-step1"), eq("472938"), isNull(), anyBoolean(),
                any(AuditContext.class))).thenThrow(new AccountInactiveException());

        assertAccountInactive(mvc.perform(post("/auth/login/mfa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaToken\":\"mfa-step1\",\"code\":\"472938\"}")));
    }

    @Test
    @DisplayName("/auth/mfa/enroll/start after a deactivation is 401 account_inactive")
    void enrollStart() throws Exception {
        when(mfaTokenService.verify(anyString(), eq(MfaTokenService.Purpose.ENROLLMENT)))
                .thenThrow(new AccountInactiveException());

        assertAccountInactive(mvc.perform(post("/auth/mfa/enroll/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaToken\":\"enrol-token\"}")));
    }

    @Test
    @DisplayName("a spent or superseded mfaToken keeps the existing 400 answer, verbatim")
    void staleMfaToken_isTheExistingInvalidTokenAnswer() throws Exception {
        when(authService.completeLoginWithMfa(eq("mfa-step1"), eq("472938"), isNull(), anyBoolean(),
                any(AuditContext.class)))
                .thenThrow(new MfaTokenService.InvalidMfaTokenException("mfaToken is invalid or expired"));

        mvc.perform(post("/auth/login/mfa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-step1\",\"code\":\"472938\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("mfaToken is invalid or expired"));
    }
}
