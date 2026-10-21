package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.AuditUnavailableException;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.UserAdminService;
import com.innbucks.userservice.testsupport.AdminDispatchHarness;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Set;

import static com.innbucks.userservice.testsupport.AdminDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * More than one SUPER_ADMIN (V52) through REAL dispatch: the controller, the
 * real {@code SuperAdminService}, {@code PlatformOwnerGuard}, {@code StaffEligibility},
 * {@code RoleGrantGuard}, {@code AccountSessionRevoker} and the real
 * {@code GlobalExceptionHandler}, over mocked repositories (see
 * {@link AdminDispatchHarness}). Each case asserts the status and body the
 * console receives.
 *
 * <p>Owner decisions (2026-10-21) pinned here: only the seeded owner promotes
 * and demotes; a deputy can do neither; a promotion needs a staff-eligible
 * account with MFA; nobody acts on the owner; a deputy cannot deactivate,
 * re-role or reset another SUPER_ADMIN through the other admin endpoints.
 */
class SuperAdminDeputiesDispatchTest {

    private static final String OWNER = "admin@innbucks.co.zw";
    private static final String DEPUTY = "rudo.deputy@innbucks.co.zw";

    private final AdminDispatchHarness h = new AdminDispatchHarness();

    /** An invite-redeemed staff account with MFA, holding a staff role. */
    private User staff(long id, String email, String... roles) {
        User user = h.eligibleStaff(h.account(id, email, roles));
        user.setMfaEnabled(true);
        return user;
    }

    private User deputy(long id, String email) {
        return staff(id, email, User.Role.CALL_CENTER_SUPERVISOR.name(), User.Role.SUPER_ADMIN.name());
    }

    // ------------------------------------------------------------------
    // Promote
    // ------------------------------------------------------------------

    @Test
    void theOwnerPromotesAnEligibleStaffAccount_keepingItsRoles_andSigningItOut() throws Exception {
        User owner = h.owner(1L, OWNER);
        User tariro = staff(4812L, "tariro.moyo@innbucks.co.zw", User.Role.CALL_CENTER_SUPERVISOR.name());

        h.mvc.perform(post("/admin/super-admins/4812").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200 OK"))
                .andExpect(jsonPath("$.message").value(AdminSuperAdminController.PROMOTED_MESSAGE))
                .andExpect(jsonPath("$.data.userId").value(4812))
                .andExpect(jsonPath("$.data.platformOwner").value(false))
                .andExpect(jsonPath("$.data.superAdmin").value(true))
                .andExpect(jsonPath("$.data.roles[0]").value("CALL_CENTER_SUPERVISOR"))
                .andExpect(jsonPath("$.data.roles[1]").value("SUPER_ADMIN"));

        assertThat(tariro.getRoles()).containsExactlyInAnyOrder("CALL_CENTER_SUPERVISOR", "SUPER_ADMIN");
        assertThat(tariro.isActive()).isTrue();
        // Signed out everywhere: bumped (published after commit), refresh
        // families revoked, device trust cleared — never deactivated.
        assertThat(tariro.getTokenVersion()).isEqualTo(4L);
        verify(h.publisher).publishAfterCommit(tariro.getUserUuid(), 4L);
        verify(h.refreshTokens).revokeAllForUser(eq(4812L), any());
        verify(h.deviceTrust).clearTrustForUser(4812L);
        // Row locks in the grant order, then the write, then the REQUIRED audit LAST.
        InOrder order = inOrder(h.roles, h.users, h.audit);
        order.verify(h.roles).lockAllByNameIn(List.of("SUPER_ADMIN"));
        order.verify(h.users).lockById(4812L);
        order.verify(h.users).save(tariro);
        order.verify(h.users).flush();
        order.verify(h.audit).recordRequired(eq(AuditEventType.SUPER_ADMIN_PROMOTED), eq(OWNER),
                eq(AuditService.ACTOR_TYPE_USER), eq("4812"), eq(AuditService.TARGET_TYPE_USER), anyMap(), any());
        assertThat(owner.getRoles()).containsExactly("SUPER_ADMIN");
    }

    @Test
    void aDeputyCannotPromote() throws Exception {
        h.owner(1L, OWNER);
        deputy(2L, DEPUTY);
        User tariro = staff(4812L, "tariro.moyo@innbucks.co.zw", User.Role.CALL_CENTER_AGENT.name());
        h.guard.setAuditService(h.audit);

        h.mvc.perform(post("/admin/super-admins/4812").principal(as(DEPUTY, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403 FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Only the platform owner can promote or demote a SUPER_ADMIN."))
                .andExpect(jsonPath("$.data.errorCode").value("platform_owner_only"));

        assertThat(tariro.getRoles()).containsExactly("CALL_CENTER_AGENT");
        verify(h.users, never()).lockById(4812L);
        verify(h.audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
        // The attempt itself is on record.
        verify(h.audit).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED), eq(DEPUTY), anyString(),
                any(), any(), eq("platform_owner_only"), anyMap(), any());
    }

    @Test
    void aMerchantAccountIsNeverPromoted() throws Exception {
        h.owner(1L, OWNER);
        User merchant = h.account(77L, "rudo@traders.co.zw", User.Role.MERCHANT_ADMIN.name());
        merchant.setMfaEnabled(true);

        h.mvc.perform(post("/admin/super-admins/77").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("super_admin_target_ineligible"))
                .andExpect(jsonPath("$.data.reason").value("off_domain"))
                .andExpect(jsonPath("$.message").value(
                        "Only an InnBucks staff account can become a SUPER_ADMIN: this email is not a staff address."));

        assertThat(merchant.getRoles()).containsExactly("MERCHANT_ADMIN");
        verify(h.audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aCustomerAccountIsNeverPromoted() throws Exception {
        h.owner(1L, OWNER);
        User customer = h.account(78L, "chipo@example.com", User.Role.CUSTOMER.name());
        customer.setMfaEnabled(true);

        h.mvc.perform(post("/admin/super-admins/78").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("super_admin_target_ineligible"))
                .andExpect(jsonPath("$.data.reason").value("off_domain"));
        assertThat(customer.hasRole(User.Role.SUPER_ADMIN)).isFalse();
    }

    @Test
    void anUnredeemedOrUnprovenStaffAddressIsRefused() throws Exception {
        h.owner(1L, OWNER);
        User invited = h.account(79L, "farai.new@innbucks.co.zw", User.Role.CALL_CENTER_AGENT.name());
        invited.setMfaEnabled(true);

        h.mvc.perform(post("/admin/super-admins/79").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("unverified"));
    }

    @Test
    void aStaffAccountAlsoHoldingABusinessRoleIsRefused() throws Exception {
        h.owner(1L, OWNER);
        User twoHats = staff(80L, "tendai.twohats@innbucks.co.zw",
                User.Role.CALL_CENTER_AGENT.name(), User.Role.MERCHANT_ADMIN.name());

        h.mvc.perform(post("/admin/super-admins/80").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("holds_non_staff_roles"));
        assertThat(twoHats.hasRole(User.Role.SUPER_ADMIN)).isFalse();
    }

    @Test
    void aDeactivatedAccountIsRefused() throws Exception {
        h.owner(1L, OWNER);
        User off = staff(81L, "off.duty@innbucks.co.zw", User.Role.CALL_CENTER_AGENT.name());
        off.setActive(false);

        h.mvc.perform(post("/admin/super-admins/81").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("account_inactive"));
    }

    @Test
    void anAccountWithoutTwoStepVerificationIsRefused() throws Exception {
        h.owner(1L, OWNER);
        User noMfa = staff(82L, "no.mfa@innbucks.co.zw", User.Role.CALL_CENTER_AGENT.name());
        noMfa.setMfaEnabled(false);

        h.mvc.perform(post("/admin/super-admins/82").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("mfa_not_enrolled"));
        assertThat(noMfa.hasRole(User.Role.SUPER_ADMIN)).isFalse();
        verify(h.refreshTokens, never()).revokeAllForUser(eq(82L), any());
    }

    @Test
    void anExistingSuperAdminIsRefused() throws Exception {
        h.owner(1L, OWNER);
        deputy(2L, DEPUTY);

        h.mvc.perform(post("/admin/super-admins/2").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("already_super_admin"));
        h.mvc.perform(post("/admin/super-admins/1").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("already_super_admin"));
    }

    @Test
    void anUnknownAccountIs404() throws Exception {
        h.owner(1L, OWNER);

        h.mvc.perform(post("/admin/super-admins/9999").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found: 9999"));
    }

    @Test
    void aPromotionThatCannotBeAuditedIsRefused503() throws Exception {
        h.owner(1L, OWNER);
        staff(4812L, "tariro.moyo@innbucks.co.zw", User.Role.CALL_CENTER_SUPERVISOR.name());
        doThrow(new AuditUnavailableException(new RuntimeException("db down"))).when(h.audit)
                .recordRequired(eq(AuditEventType.SUPER_ADMIN_PROMOTED), any(), any(), any(), any(), any(), any());

        h.mvc.perform(post("/admin/super-admins/4812").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("audit_unavailable"));
    }

    // ------------------------------------------------------------------
    // Demote
    // ------------------------------------------------------------------

    @Test
    void theOwnerDemotesADeputy_keepingItActiveWithItsOtherRoles_andSigningItOut() throws Exception {
        h.owner(1L, OWNER);
        User rudo = deputy(2L, DEPUTY);

        h.mvc.perform(delete("/admin/super-admins/2").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(AdminSuperAdminController.DEMOTED_MESSAGE))
                .andExpect(jsonPath("$.data.superAdmin").value(false))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.roles[0]").value("CALL_CENTER_SUPERVISOR"));

        assertThat(rudo.getRoles()).containsExactly("CALL_CENTER_SUPERVISOR");
        assertThat(rudo.isActive()).isTrue();
        assertThat(rudo.getTokenVersion()).isEqualTo(4L);
        verify(h.publisher).publishAfterCommit(rudo.getUserUuid(), 4L);
        verify(h.refreshTokens).revokeAllForUser(eq(2L), any());
        verify(h.deviceTrust).clearTrustForUser(2L);
        verify(h.audit).recordRequired(eq(AuditEventType.SUPER_ADMIN_DEMOTED), eq(OWNER),
                eq(AuditService.ACTOR_TYPE_USER), eq("2"), eq(AuditService.TARGET_TYPE_USER), anyMap(), any());
    }

    @Test
    void nobodyDemotesThePlatformOwner_notEvenTheOwner() throws Exception {
        User owner = h.owner(1L, OWNER);

        h.mvc.perform(delete("/admin/super-admins/1").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_manageable"))
                .andExpect(jsonPath("$.data.reason").value("platform_owner"));

        assertThat(owner.getRoles()).containsExactly("SUPER_ADMIN");
        assertThat(owner.getTokenVersion()).isEqualTo(3L);
    }

    @Test
    void aDeputyCannotDemoteTheOwnerOrAnotherDeputy() throws Exception {
        User owner = h.owner(1L, OWNER);
        deputy(2L, DEPUTY);
        User other = deputy(3L, "other.deputy@innbucks.co.zw");

        h.mvc.perform(delete("/admin/super-admins/1").principal(as(DEPUTY, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("platform_owner_only"));
        h.mvc.perform(delete("/admin/super-admins/3").principal(as(DEPUTY, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("platform_owner_only"));

        assertThat(owner.hasRole(User.Role.SUPER_ADMIN)).isTrue();
        assertThat(other.hasRole(User.Role.SUPER_ADMIN)).isTrue();
    }

    @Test
    void demotingAnAccountThatIsNotASuperAdminIs409() throws Exception {
        h.owner(1L, OWNER);
        staff(4812L, "tariro.moyo@innbucks.co.zw", User.Role.CALL_CENTER_AGENT.name());

        h.mvc.perform(delete("/admin/super-admins/4812").principal(as(OWNER, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("not_super_admin"));
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    @Test
    void theListPutsTheOwnerFirst() throws Exception {
        User owner = h.owner(1L, OWNER);
        owner.setFirstName("Zed");
        User rudo = deputy(2L, DEPUTY);
        rudo.setFirstName("Anesu");
        when(h.users.findByAnyRole(Set.of("SUPER_ADMIN"))).thenReturn(List.of(rudo, owner));

        h.mvc.perform(get("/admin/super-admins").principal(as(DEPUTY, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(1))
                .andExpect(jsonPath("$.data[0].platformOwner").value(true))
                .andExpect(jsonPath("$.data[1].userId").value(2))
                .andExpect(jsonPath("$.data[1].platformOwner").value(false));
    }

    // ------------------------------------------------------------------
    // The other admin endpoints still refuse every SUPER_ADMIN target
    // ------------------------------------------------------------------

    @Test
    void aDeputyCannotDeactivateTheOwnerOrAnotherDeputy() throws Exception {
        User owner = h.owner(1L, OWNER);
        deputy(2L, DEPUTY);
        User other = deputy(3L, "other.deputy@innbucks.co.zw");

        for (long target : new long[]{1L, 3L}) {
            h.mvc.perform(put("/admin/users/" + target + "/active").principal(as(DEPUTY, "ROLE_SUPER_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(UserAdminService.SUPER_ADMIN_ACTIVE_MESSAGE));
        }
        assertThat(owner.isActive()).isTrue();
        assertThat(other.isActive()).isTrue();
    }

    @Test
    void aDeputyCannotResetTheOwnersSecondFactor() throws Exception {
        User owner = h.owner(1L, OWNER);
        deputy(2L, DEPUTY);

        h.mvc.perform(post("/admin/users/1/mfa/reset").principal(as(DEPUTY, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_manageable"))
                .andExpect(jsonPath("$.data.reason").value("super_admin"));
        assertThat(owner.isMfaEnabled()).isTrue();
    }

    @Test
    void setRolesStillRefusesGrantingSuperAdmin_evenForTheOwner() throws Exception {
        h.owner(1L, OWNER);
        User tariro = staff(4812L, "tariro.moyo@innbucks.co.zw", User.Role.CALL_CENTER_SUPERVISOR.name());

        h.mvc.perform(put("/admin/users/4812/roles").principal(as(OWNER, "ROLE_SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\": [\"CALL_CENTER_SUPERVISOR\", \"SUPER_ADMIN\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(UserAdminService.SUPER_ADMIN_GRANT_MESSAGE));
        assertThat(tariro.getRoles()).containsExactly("CALL_CENTER_SUPERVISOR");
    }

    @Test
    void setRolesStillRefusesEverySuperAdminTarget_soADeputyIsDemotedOnlyByTheOwnersEndpoint() throws Exception {
        h.owner(1L, OWNER);
        User rudo = deputy(2L, DEPUTY);

        h.mvc.perform(put("/admin/users/2/roles").principal(as(OWNER, "ROLE_SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\": [\"CALL_CENTER_SUPERVISOR\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(UserAdminService.SUPER_ADMIN_ROLES_MESSAGE));
        assertThat(rudo.hasRole(User.Role.SUPER_ADMIN)).isTrue();
    }
}
