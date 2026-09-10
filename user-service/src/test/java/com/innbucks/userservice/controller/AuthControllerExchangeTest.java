package com.innbucks.userservice.controller;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.dto.AuthResponseDTO;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the HTTP contract of {@code POST /auth/exchange}: the body shape, the
 * device header, the login-shaped 200, and that the service's typed refusals
 * reach the client with their status intact. Standalone MockMvc — no Spring
 * context, no Docker; the fourteen unrelated collaborators are inert mocks.
 */
class AuthControllerExchangeTest {

    private FederatedLoginService federatedLoginService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        federatedLoginService = mock(FederatedLoginService.class);
        AuthController controller = new AuthController(
                mock(AuthService.class), mock(CustomerService.class), mock(TokenRevocationService.class),
                mock(OtpService.class), mock(CellAffinityChecker.class), mock(JwtUtil.class),
                mock(LoyaltySessionTokenIssuer.class), mock(LoginRateLimiter.class), mock(AuditService.class),
                mock(PasswordResetService.class), mock(MfaService.class), mock(MfaTokenService.class),
                mock(MfaPolicy.class), mock(UserRepository.class), federatedLoginService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a valid assertion yields the same envelope and message as /auth/login, device id passed through")
    void exchange_returnsLoginShapedResponse() throws Exception {
        when(federatedLoginService.exchange(eq("a.b.c"), eq("device-1"), any(AuditContext.class)))
                .thenReturn(AuthResponseDTO.builder()
                        .token("access").refreshToken("refresh").roles(List.of("CUSTOMER"))
                        .tier(1).verified(false).build());

        mvc.perform(post("/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device-Id", "device-1")
                        .content("{\"assertion\":\"a.b.c\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200 OK"))
                .andExpect(jsonPath("$.message").value("Login successful"))
                .andExpect(jsonPath("$.data.token").value("access"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.data.roles[0]").value("CUSTOMER"));
    }

    @Test
    @DisplayName("the device header is optional")
    void exchange_deviceIdIsOptional() throws Exception {
        when(federatedLoginService.exchange(eq("a.b.c"), isNull(), any(AuditContext.class)))
                .thenReturn(AuthResponseDTO.builder().token("t").refreshToken("r").roles(List.of("CUSTOMER")).build());

        mvc.perform(post("/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assertion\":\"a.b.c\"}"))
                .andExpect(status().isOk());

        verify(federatedLoginService).exchange(eq("a.b.c"), isNull(), any(AuditContext.class));
    }

    @Test
    @DisplayName("a missing assertion is a 400 before the service is consulted")
    void exchange_missingAssertionIs400() throws Exception {
        mvc.perform(post("/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(federatedLoginService);
    }

    @Test
    @DisplayName("the service's opaque 401 reaches the client with its status and reason intact")
    void exchange_rejectionIs401() throws Exception {
        when(federatedLoginService.exchange(any(), any(), any(AuditContext.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, FederatedLoginService.REJECTED_REASON));

        mvc.perform(post("/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assertion\":\"forged\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Assertion rejected"));
    }

    @Test
    @DisplayName("a disabled cell answers 404 — the GlobalExceptionHandler must not collapse it to 400")
    void exchange_disabledIs404() throws Exception {
        when(federatedLoginService.exchange(any(), any(), any(AuditContext.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));

        mvc.perform(post("/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assertion\":\"a.b.c\"}"))
                .andExpect(status().isNotFound());
    }
}
