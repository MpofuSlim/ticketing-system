package com.innbucks.userservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.dto.RejectRegistrationDTO;
import com.innbucks.userservice.dto.RejectedRegistrationDTO;
import com.innbucks.userservice.exception.AuditUnavailableException;
import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.exception.RegistrationRejectionException;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.RegistrationRejectionService;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /admin/users/{id}/reject} through real dispatch (standalone MockMvc
 * plus the real {@link GlobalExceptionHandler}): the success envelope, bean
 * validation, and the status, {@code errorCode} and message every refusal of
 * {@link RegistrationRejectionService} reaches the console with. Permission
 * enforcement is Spring Security's and is pinned here by reflection — the
 * standalone setup runs without method security.
 */
class AdminRegistrationControllerTest {

    private static final String ADMIN = "ops.lead@innbucks.co.zw";
    private static final String REASON = "We couldn't verify the BPO number you gave.";

    private RegistrationRejectionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RegistrationRejectionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminRegistrationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken(ADMIN, null,
                List.of(new SimpleGrantedAuthority("users:activation:write")));
    }

    private ResultActions reject(String body) throws Exception {
        return mvc.perform(put("/admin/users/57/reject")
                .principal(admin())
                .header("X-Forwarded-For", "41.221.10.5, 10.0.0.7")
                .header("User-Agent", "foundry-console")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions rejectRefusedWith(RuntimeException refusal) throws Exception {
        doThrow(refusal).when(service).reject(anyLong(), anyString(), anyString(), any());
        return reject("{\"reason\":\"" + REASON + "\"}");
    }

    @Test
    @DisplayName("200: the envelope names the removed account, and the caller and request reach the service")
    void rejected() throws Exception {
        when(service.reject(eq(57L), eq(REASON), eq(ADMIN), any())).thenReturn(new RejectedRegistrationDTO(
                57L, "rumbi@showtime.co.zw", REASON, LocalDateTime.of(2026, 10, 8, 12, 30)));

        reject("{\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200 OK"))
                .andExpect(jsonPath("$.message").value("Registration rejected"))
                .andExpect(jsonPath("$.data.id").value(57))
                .andExpect(jsonPath("$.data.email").value("rumbi@showtime.co.zw"))
                .andExpect(jsonPath("$.data.reason").value(REASON))
                .andExpect(jsonPath("$.data.rejectedAt").exists());

        ArgumentCaptor<AuditContext> context = ArgumentCaptor.forClass(AuditContext.class);
        verify(service).reject(eq(57L), eq(REASON), eq(ADMIN), context.capture());
        assertThat(context.getValue()).isEqualTo(new AuditContext("41.221.10.5", "foundry-console"));
    }

    @Test
    @DisplayName("400: a missing or blank reason, before the service is reached")
    void reasonRequired() throws Exception {
        for (String body : List.of("{}", "{\"reason\":\"   \"}", "{\"reason\":null}")) {
            reject(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.data.reason").value("reason is required"));
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("400: a reason over 1000 characters")
    void reasonTooLong() throws Exception {
        reject("{\"reason\":\"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.reason").value("reason must be 1000 characters or fewer"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("400: no body at all")
    void noBody() throws Exception {
        mvc.perform(put("/admin/users/57/reject").principal(admin()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("We couldn't process your request. Please try again."));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("404: no such account")
    void notFound() throws Exception {
        rejectRefusedWith(new NotFoundException("User not found: 57"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404 NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("User not found: 57"));
    }

    @Test
    @DisplayName("403: the SUPER_ADMIN")
    void superAdmin() throws Exception {
        rejectRefusedWith(new ResponseStatusException(HttpStatus.FORBIDDEN, "The SUPER_ADMIN account cannot be rejected."))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("The SUPER_ADMIN account cannot be rejected."));
    }

    @Test
    @DisplayName("409 registration_already_decided")
    void alreadyDecided() throws Exception {
        rejectRefusedWith(RegistrationRejectionException.alreadyDecided())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409 CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("This account has already been approved. Deactivate it instead of rejecting it."))
                .andExpect(jsonPath("$.data.errorCode").value("registration_already_decided"));
    }

    @Test
    @DisplayName("409 use_staff_endpoints")
    void staffAccount() throws Exception {
        rejectRefusedWith(StaffPolicyException.useStaffEndpoints())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("use_staff_endpoints"));
    }

    @Test
    @DisplayName("409 registration_business_in_use carries the reason")
    void businessInUse() throws Exception {
        rejectRefusedWith(RegistrationRejectionException.businessInUse(
                RegistrationRejectionException.REASON_LOYALTY_MERCHANT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This business already has a loyalty merchant in "
                        + "InnRewards. Remove it there first, or approve the registration."))
                .andExpect(jsonPath("$.data.errorCode").value("registration_business_in_use"))
                .andExpect(jsonPath("$.data.reason").value("loyalty_merchant"));
    }

    @Test
    @DisplayName("409 registration_changed")
    void changed() throws Exception {
        rejectRefusedWith(RegistrationRejectionException.changed())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("This registration changed while it was being rejected. Refresh and try again."))
                .andExpect(jsonPath("$.data.errorCode").value("registration_changed"));
    }

    @Test
    @DisplayName("503 registration_check_unavailable and audit_unavailable")
    void unavailable() throws Exception {
        rejectRefusedWith(RegistrationRejectionException.checkUnavailable())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("503 SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.errorCode").value("registration_check_unavailable"));

        rejectRefusedWith(new AuditUnavailableException(new IllegalStateException("audit store down")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("We couldn't record this change, so it wasn't made. Try again."))
                .andExpect(jsonPath("$.data.errorCode").value("audit_unavailable"));
    }

    private static Method handler() throws NoSuchMethodException {
        return AdminRegistrationController.class.getMethod("reject",
                Long.class, RejectRegistrationDTO.class, Authentication.class, HttpServletRequest.class);
    }

    @Test
    @DisplayName("gated on users:activation:write — the same permission as approving")
    void sameAuthorityAsApproving() throws Exception {
        Method approve = AdminUserController.class.getMethod("updateActiveStatus", Long.class,
                com.innbucks.userservice.dto.UpdateActiveStatusDTO.class, Authentication.class, HttpServletRequest.class);
        assertThat(handler().getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('users:activation:write')")
                .isEqualTo(approve.getAnnotation(PreAuthorize.class).value());
    }

    /**
     * A Swagger example is code nothing executes, so the refusal examples are
     * pinned to the refusals the service actually throws: every
     * {@code errorCode} example quotes a real (status, message, code, reason),
     * and every real refusal has an example.
     */
    @Test
    @DisplayName("every errorCode example in the Swagger is a refusal the code throws, and vice versa")
    void swaggerExamplesMatchTheCode() throws Exception {
        Set<String> documented = new HashSet<>();
        ObjectMapper json = new ObjectMapper();
        for (ApiResponse response : handler().getAnnotation(ApiResponses.class).value()) {
            for (ExampleObject example : response.content()[0].examples()) {
                JsonNode body = json.readTree(example.value());
                assertThat(body.get("code").asText()).as(example.name()).startsWith(response.responseCode() + " ");
                if (body.path("data").has("errorCode")) {
                    documented.add(describe(body.get("code").asText(), body.get("message").asText(),
                            body.path("data").get("errorCode").asText(), body.path("data").path("reason").asText(null)));
                }
            }
        }

        Set<String> thrown = new HashSet<>();
        List<StaffPolicyException> refusals = new java.util.ArrayList<>(List.of(
                RegistrationRejectionException.alreadyDecided(),
                RegistrationRejectionException.changed(),
                RegistrationRejectionException.checkUnavailable(),
                StaffPolicyException.useStaffEndpoints(),
                new AuditUnavailableException(null)));
        for (String reason : List.of("other_members", "loyalty_merchant", "team_members", "customer_account",
                "sole_owner_elsewhere")) {
            refusals.add(RegistrationRejectionException.businessInUse(reason));
        }
        for (StaffPolicyException ex : refusals) {
            thrown.add(describe(ex.getStatus().value() + " " + ex.getStatus().name(), ex.getMessage(),
                    ex.getErrorCode(), (String) ex.getExtra().get("reason")));
        }

        assertThat(documented).isEqualTo(thrown);
    }

    private static String describe(String code, String message, String errorCode, String reason) {
        return code + " | " + errorCode + (reason == null ? "" : "/" + reason) + " | " + message;
    }
}
