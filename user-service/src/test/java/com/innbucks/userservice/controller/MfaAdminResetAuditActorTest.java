package com.innbucks.userservice.controller;

import com.innbucks.userservice.config.MfaProperties;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.repository.MfaBackupCodeRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.DeviceTrustService;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.service.TokenVersionBumper;
import com.innbucks.userservice.service.UserAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /admin/users/{id}/mfa/reset}, through real dispatch (standalone
 * MockMvc + the real {@link GlobalExceptionHandler}) into the real
 * {@link MfaService}:
 * <ul>
 *   <li>the audit row names the ADMIN as actor and the USER as target — it used
 *       to name the target as its own actor, so nobody could say who reset
 *       whose 2FA;</li>
 *   <li>the optional note lands on that row;</li>
 *   <li>the reset bumps {@code tokenVersion}, so it takes effect at once;</li>
 *   <li>a SUPER_ADMIN target is a 403 {@code target_not_manageable} and nothing
 *       is touched.</li>
 * </ul>
 * Permission enforcement ({@code @PreAuthorize}) is Spring Security's and is not
 * exercised here.
 */
class MfaAdminResetAuditActorTest {

    private static final String ADMIN = "ops.lead@innbucks.co.zw";

    private UserRepository users;
    private MfaBackupCodeRepository backupCodes;
    private TokenVersionBumper bumper;
    private AuditService audit;
    private DeviceTrustService deviceTrust;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        backupCodes = mock(MfaBackupCodeRepository.class);
        bumper = mock(TokenVersionBumper.class);
        audit = mock(AuditService.class);
        deviceTrust = mock(DeviceTrustService.class);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bumper.bump(any(User.class))).thenReturn(8L);

        // The reset needs the caller to hold everything the target holds, read
        // from their live roles: the administrator here is the platform owner.
        com.innbucks.userservice.repository.RoleRepository roles =
                mock(com.innbucks.userservice.repository.RoleRepository.class);
        com.innbucks.userservice.testsupport.BuiltInRoleRows.stub(roles);
        com.innbucks.userservice.testsupport.BuiltInRoleRows.caller(users, ADMIN, User.Role.SUPER_ADMIN.name());
        MfaService mfaService = new MfaService(users, backupCodes, mock(PasswordEncoder.class),
                new MfaProperties(), bumper, new com.innbucks.userservice.service.RoleGrantGuard(users, roles));
        ReflectionTestUtils.setField(mfaService, "auditService", audit);
        ReflectionTestUtils.setField(mfaService, "deviceTrustService", deviceTrust);

        AdminUserController controller = new AdminUserController(users, mock(TenantProfileRepository.class),
                mock(UserAdminService.class), mfaService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken(ADMIN, null,
                List.of(new SimpleGrantedAuthority("users:mfa:reset")));
    }

    private User target(long id, User.Role role) {
        User u = User.builder().id(id).userUuid(UUID.randomUUID()).email("tendai@acme.co.zw")
                .roles(User.roleNames(role)).active(true).mfaEnabled(true).mfaSecret("JBSWY3DPEHPK3PXP")
                .tokenVersion(7L).build();
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("the audit row names the admin as actor and the user as target, with the note")
    @SuppressWarnings("unchecked")
    void actorIsTheAdmin_targetIsTheUser() throws Exception {
        User user = target(13L, User.Role.MERCHANT_ADMIN);

        mvc.perform(post("/admin/users/13/mfa/reset")
                        .principal(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Lost phone; identity confirmed by callback.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("MFA reset"));

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordSuccess(eq(AuditEventType.MFA_ADMIN_RESET),
                eq(ADMIN), eq(AuditService.ACTOR_TYPE_USER),
                eq("13"), eq(AuditService.TARGET_TYPE_USER),
                metadata.capture(), any(AuditContext.class));
        assertThat(metadata.getValue())
                .containsEntry("note", "Lost phone; identity confirmed by callback.")
                .containsEntry("tokenVersion", 8L)
                .containsEntry("targetEmail", "tendai@acme.co.zw");
        assertThat(user.isMfaEnabled()).isFalse();
        assertThat(user.getMfaSecret()).isNull();
        verify(backupCodes).deleteAllForUser(13L);
        verify(deviceTrust).clearTrustForUser(13L);
    }

    @Test
    @DisplayName("the reset ends the user's sessions and pending 2FA challenges at once")
    void theResetBumpsTokenVersion() throws Exception {
        User user = target(14L, User.Role.PRODUCT_OFFICER);

        mvc.perform(post("/admin/users/14/mfa/reset").principal(admin()))
                .andExpect(status().isOk());

        verify(bumper).bump(user);
    }

    @Test
    @DisplayName("the body is optional; a missing note records no note")
    @SuppressWarnings("unchecked")
    void noBody_isFine() throws Exception {
        target(15L, User.Role.EVENT_ORGANIZER);

        mvc.perform(post("/admin/users/15/mfa/reset").principal(admin()))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordSuccess(eq(AuditEventType.MFA_ADMIN_RESET), eq(ADMIN), anyString(),
                eq("15"), anyString(), metadata.capture(), any(AuditContext.class));
        assertThat(metadata.getValue()).doesNotContainKey("note");
    }

    @Test
    @DisplayName("a SUPER_ADMIN target is 403 target_not_manageable and nothing is touched")
    void superAdminTarget_isRefused() throws Exception {
        User owner = target(1L, User.Role.SUPER_ADMIN);

        mvc.perform(post("/admin/users/1/mfa/reset").principal(admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403 FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("You can't change this account."))
                .andExpect(jsonPath("$.data.errorCode").value("target_not_manageable"))
                .andExpect(jsonPath("$.data.reason").value("super_admin"));

        assertThat(owner.isMfaEnabled()).isTrue();
        assertThat(owner.getMfaSecret()).isEqualTo("JBSWY3DPEHPK3PXP");
        verify(users, never()).save(any());
        verifyNoInteractions(bumper, backupCodes, deviceTrust, audit);
    }

    @Test
    @DisplayName("a note over 500 characters is the standard validation 400")
    void longNote_is400() throws Exception {
        target(16L, User.Role.MERCHANT_ADMIN);
        String note = "x".repeat(501);

        mvc.perform(post("/admin/users/16/mfa/reset")
                        .principal(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + note + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.note").value("note must be 500 characters or fewer"));

        verifyNoInteractions(bumper, audit);
    }

    @Test
    @DisplayName("an unknown id is 404 with the service's own message")
    void unknownUser_is404() throws Exception {
        when(users.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(post("/admin/users/999/mfa/reset").principal(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found: 999"));

        verify(audit, never()).recordSuccess(any(), anyString(), anyString(), anyString(), anyString(),
                anyMap(), any());
    }
}
