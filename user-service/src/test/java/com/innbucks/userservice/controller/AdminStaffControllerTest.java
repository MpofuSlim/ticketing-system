package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.StaffInvite;
import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /admin/staff}, {@code GET /admin/staff} and {@code /{id}} through
 * real dispatch (V44): every status and body the console branches on, including
 * the insert race answered as 409 {@code email_taken} rather than the generic 400.
 */
class AdminStaffControllerTest {

    private static final String BODY = """
            {
              "firstName": "Tariro",
              "lastName": "Moyo",
              "email": "Tariro.Moyo@InnBucks.co.zw",
              "phoneNumber": "+263771234567",
              "country": "Zimbabwe",
              "roles": ["CALL_CENTER_AGENT"],
              "note": "Joins the Harare call-center team on 1 Oct (HR-2291)."
            }
            """;

    private StaffDispatchHarness h;

    @BeforeEach
    void setUp() {
        h = new StaffDispatchHarness();
    }

    private ResultActions create(String caller, String body) throws Exception {
        return h.mvc.perform(post("/admin/staff").principal(as(caller, "staff:create"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(String email, String roles) {
        return BODY.replace("Tariro.Moyo@InnBucks.co.zw", email).replace("[\"CALL_CENTER_AGENT\"]", roles);
    }

    @Test
    @DisplayName("201: an INVITED staff account, no sign-in phone, an invite minted and emailed after commit")
    void created() throws Exception {
        create(OWNER, BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("201 CREATED"))
                .andExpect(jsonPath("$.message").value(
                        "Staff account created. We're emailing an invite to tariro.moyo@innbucks.co.zw."))
                .andExpect(jsonPath("$.data.email").value("tariro.moyo@innbucks.co.zw"))
                .andExpect(jsonPath("$.data.phoneNumber").value("+263771234567"))
                .andExpect(jsonPath("$.data.status").value("INVITED"))
                .andExpect(jsonPath("$.data.emailVerified").value(false))
                .andExpect(jsonPath("$.data.emailDomainAllowed").value(true))
                .andExpect(jsonPath("$.data.mfaEnrolled").value(false))
                .andExpect(jsonPath("$.data.roles[0]").value("CALL_CENTER_AGENT"))
                .andExpect(jsonPath("$.data.permissions", containsInAnyOrder(
                        "device-security:read", "device-security:manage")))
                .andExpect(jsonPath("$.data.manageable").value(true))
                .andExpect(jsonPath("$.data.invite.sentTo").value("tariro.moyo@innbucks.co.zw"))
                .andExpect(jsonPath("$.data.invite.deliveryStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.whatHappensNext").exists());

        User created = h.userRows.values().stream()
                .filter(u -> "tariro.moyo@innbucks.co.zw".equals(u.getEmail())).findFirst().orElseThrow();
        assertThat(created.getPhoneNumber()).isNull();
        assertThat(created.getEmailVerifiedAt()).isNull();
        assertThat(created.isActive()).isTrue();
        assertThat(created.isApproved()).isTrue();
        assertThat(created.isMustChangePassword()).isFalse();
        assertThat(created.getHomeCountry()).isEqualTo("ZW");
        assertThat(created.getPassword()).startsWith("{x}!INVITE-");
        StaffProfile profile = h.profileRows.get(created.getId());
        assertThat(profile.getContactPhone()).isEqualTo("+263771234567");
        assertThat(profile.isAdopted()).isFalse();
        assertThat(profile.getInviteAcceptedAt()).isNull();
        StaffInvite invite = h.inviteRows.values().iterator().next();
        assertThat(invite.getSentToEmail()).isEqualTo("tariro.moyo@innbucks.co.zw");
        assertThat(invite.getCreatedByEmail()).isEqualTo(OWNER);
        assertThat(invite.getExpiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(71));
        assertThat(invite.getTokenHash()).isEqualTo(StaffDispatchHarness.hash(h.lastRawToken()));
        verify(h.audit).recordRequired(eq(AuditEventType.STAFF_INVITED), eq(OWNER), any(),
                eq(String.valueOf(created.getId())), eq("USER"), any(), any());
    }

    @Test
    @DisplayName("400 Validation failed: the field map, never an errorCode")
    void validation() throws Exception {
        create(OWNER, "{\"firstName\":\"Tariro\",\"lastName\":\"Moyo\",\"email\":\"t@innbucks.co.zw\","
                + "\"country\":\"Zimbabwe\",\"roles\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.note").value("note is required"))
                .andExpect(jsonPath("$.data.roles").value("At least one role is required"))
                .andExpect(jsonPath("$.data.errorCode").doesNotExist());
    }

    @Test
    @DisplayName("400 email_domain_not_allowed names the configured domains")
    void offDomain() throws Exception {
        create(OWNER, body("tariro@gmail.com", "[\"CALL_CENTER_AGENT\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Staff accounts must use an InnBucks email address ending in "
                        + "@innbucks.co.zw or @innbucks.co.ke."))
                .andExpect(jsonPath("$.data.errorCode").value("email_domain_not_allowed"))
                .andExpect(jsonPath("$.data.field").value("email"))
                .andExpect(jsonPath("$.data.allowedDomains[0]").value("innbucks.co.zw"));
        assertThat(h.userRows).hasSize(1);
        verify(h.audit).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED), eq(OWNER), any(), any(), any(),
                eq("email_domain_not_allowed"), any(), any());
    }

    @Test
    @DisplayName("400 email_not_accepted: plus tag, shared mailbox")
    void notAccepted() throws Exception {
        create(OWNER, body("tariro+ops@innbucks.co.zw", "[\"CALL_CENTER_AGENT\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Use the person's own InnBucks address, without a +tag, "
                        + "special characters or a shared mailbox name."))
                .andExpect(jsonPath("$.data.errorCode").value("email_not_accepted"))
                .andExpect(jsonPath("$.data.reason").value("plus_tag"));
        create(OWNER, body("support@innbucks.co.zw", "[\"CALL_CENTER_AGENT\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reason").value("shared_mailbox"));
    }

    @Test
    @DisplayName("400 role_not_assignable: every refused role in ONE answer (reserved, unknown, not a staff role, authority)")
    void roles() throws Exception {
        create(OWNER, body("tariro.moyo@innbucks.co.zw", "[\"SUPER_ADMIN\",\"MERCHANT_ADMIN\",\"NOPE\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("These roles can't be given to this account: "
                        + "MERCHANT_ADMIN (not a staff role), NOPE (unknown), SUPER_ADMIN (reserved)."))
                .andExpect(jsonPath("$.data.errorCode").value("role_not_assignable"))
                .andExpect(jsonPath("$.data.roles.SUPER_ADMIN").value("reserved"))
                .andExpect(jsonPath("$.data.roles.NOPE").value("unknown"))
                .andExpect(jsonPath("$.data.roles.MERCHANT_ADMIN").value("not_a_staff_role"));

        // A non-wildcard creator (hypothetically granted staff:create) is bound by no-escalation.
        h.eligibleStaff("lead@innbucks.co.zw", "CALL_CENTER_SUPERVISOR");
        create("lead@innbucks.co.zw", body("tariro.moyo@innbucks.co.zw", "[\"FRAUD_DESK\",\"CALL_CENTER_AGENT\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.roles.FRAUD_DESK").value("exceeds_your_authority"))
                .andExpect(jsonPath("$.data.roles.CALL_CENTER_AGENT").value("named_role_not_held"));
        assertThat(h.userRows).hasSize(2);
    }

    @Test
    @DisplayName("400 invalid_request on an unusable contact number")
    void badPhone() throws Exception {
        create(OWNER, BODY.replace("+263771234567", "12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That isn't a valid mobile number."))
                .andExpect(jsonPath("$.data.errorCode").value("invalid_request"))
                .andExpect(jsonPath("$.data.field").value("phoneNumber"));
    }

    @Test
    @DisplayName("409 email_taken — case-insensitively, and on a lost insert race (not the generic 400)")
    void emailTaken() throws Exception {
        h.account("tariro.moyo@innbucks.co.zw", "CUSTOMER");
        create(OWNER, BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists."))
                .andExpect(jsonPath("$.data.errorCode").value("email_taken"));

        StaffDispatchHarness racing = new StaffDispatchHarness();
        when(racing.users.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uk_users_email"));
        racing.mvc.perform(post("/admin/staff").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("email_taken"))
                .andExpect(jsonPath("$.data.field").value("email"));
        verify(racing.audit, never()).recordRequired(eq(AuditEventType.STAFF_INVITED), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    @DisplayName("429 staff_create_limited with Retry-After once the caller's daily quota is spent")
    void quota() throws Exception {
        h.properties.setCreateDailyLimit(1);
        create(OWNER, BODY).andExpect(status().isCreated());
        // JPA auditing stamps the creator; the harness has no auditing, so stamp it.
        h.userRows.values().stream().filter(u -> u.getEmail().startsWith("tariro"))
                .forEach(u -> u.setCreatedBy(OWNER));
        create(OWNER, body("farai.chikwanha@innbucks.co.zw", "[\"CALL_CENTER_AGENT\"]"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.data.errorCode").value("staff_create_limited"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").isNumber());
    }

    @Test
    @DisplayName("503 before anything is created: no staff domain, or no way to send an invite")
    void unconfigured() throws Exception {
        h.properties.setAllowedEmailDomains(List.of());
        h.properties.afterPropertiesSet();
        create(OWNER, BODY)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Staff accounts aren't set up on this server yet."))
                .andExpect(jsonPath("$.data.errorCode").value("staff_domains_unconfigured"));

        StaffDispatchHarness noMail = new StaffDispatchHarness();
        when(noMail.email.apiConfigured()).thenReturn(false);
        when(noMail.email.smtpEnabled()).thenReturn(false);
        noMail.mvc.perform(post("/admin/staff").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("staff_invites_unconfigured"));
        assertThat(noMail.userRows).hasSize(1);
        assertThat(noMail.invitesRequested).isEmpty();
    }

    @Test
    @DisplayName("GET /{id}: the view; 404 staff_not_found for a non-staff account")
    void getOne() throws Exception {
        create(OWNER, BODY).andExpect(status().isCreated());
        User created = h.userRows.values().stream()
                .filter(u -> "tariro.moyo@innbucks.co.zw".equals(u.getEmail())).findFirst().orElseThrow();
        h.mvc.perform(get("/admin/staff/{id}", created.getId()).principal(as(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVITED"))
                .andExpect(jsonPath("$.data.invite.sentTo").value("tariro.moyo@innbucks.co.zw"));
        User customer = h.account("rudo@example.com", "CUSTOMER");
        h.mvc.perform(get("/admin/staff/{id}", customer.getId()).principal(as(OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Staff account not found."))
                .andExpect(jsonPath("$.data.errorCode").value("staff_not_found"));
    }

    @Test
    @DisplayName("GET list: filters by role, status and q; legacy rows carry adoptable and the blocker")
    void list() throws Exception {
        create(OWNER, BODY).andExpect(status().isCreated());
        User legacy = h.account("farai.chikwanha@innbucks.co.zw", "PRODUCT_OFFICER");
        h.account("owner@shop.co.zw", "MERCHANT_ADMIN");
        User offDomain = h.account("pm@gmail.com", "PRODUCT_MANAGER");

        h.mvc.perform(get("/admin/staff").principal(as(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content[?(@.email=='owner@shop.co.zw')]").isEmpty());
        h.mvc.perform(get("/admin/staff").param("status", "INVITED").principal(as(OWNER)))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("tariro.moyo@innbucks.co.zw"));
        h.mvc.perform(get("/admin/staff").param("role", "PRODUCT_OFFICER").principal(as(OWNER)))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].adoptable").value(true));
        h.mvc.perform(get("/admin/staff").param("q", "gmail").principal(as(OWNER)))
                .andExpect(jsonPath("$.data.content[0].id").value(offDomain.getId()))
                .andExpect(jsonPath("$.data.content[0].adoptable").value(false))
                .andExpect(jsonPath("$.data.content[0].adoptionBlockedReason").value("off_domain"));
        assertThat(legacy.getId()).isNotNull();
    }
}
