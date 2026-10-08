package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.notification.UserNotificationDispatcher;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.UserAdminService;
import com.innbucks.userservice.testsupport.SessionRevocationItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /admin/users/{id}/reject} against real Postgres: a registration made
 * through the real {@code POST /auth/register}, rejected through the real
 * endpoint, leaves NO row behind in any table that referenced it — and the same
 * email, phone and BPO number then register again.
 *
 * <p>Real transactions throughout, so the IT also proves what a mocked one
 * cannot: the cascades V3/V4/V24/V39 declare actually fire, the dependents with
 * no cascade (V1, V8) are deleted before the account, InnRewards is asked with
 * no transaction open, an approval that commits between the check and the row
 * lock turns the rejection into {@code registration_changed}, and a rejection
 * whose audit row Postgres refuses removes nothing and tells nobody.
 *
 * <p>InnRewards and the notification dispatcher are mocked beans; the addresses
 * are ordinary business ones (InnBucks staff addresses cannot register).
 */
class RegistrationRejectionPostgresIT extends SessionRevocationItSupport {

    private static final String REASON = "We couldn't verify the BPO number you gave. Please register again with "
            + "the number on your ZIMRA certificate.";

    @MockitoBean LoyaltyServiceClient loyalty;
    @MockitoBean UserNotificationDispatcher dispatcher;

    @Autowired JdbcTemplate jdbc;
    @Autowired UserAdminService userAdminService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired OrganizationMemberRepository memberRepository;

    /** Whether a transaction was open at each InnRewards call. */
    private final List<Boolean> loyaltyCallsInTransaction = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void innRewardsKnowsNoMerchant() {
        reset(loyalty, dispatcher);
        loyaltyCallsInTransaction.clear();
        when(loyalty.merchantIdsForOrganizationIfKnown(any())).thenAnswer(inv -> {
            loyaltyCallsInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(List.of());
        });
    }

    @AfterEach
    void dropTheAuditTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_rejection_audit ON audit_events");
        jdbc.execute("DROP FUNCTION IF EXISTS it_fail_rejection_audit()");
    }

    /** The details a registration takes; the same three are used again after the rejection. */
    private record Applicant(String email, String phone, String bpo) {
    }

    private static Applicant freshApplicant() {
        return new Applicant("owner-" + unique() + "@showtime-events.co.zw", phone(),
                "2001-" + (100_000 + ThreadLocalRandom.current().nextInt(899_999)));
    }

    private ResultActions register(Applicant applicant) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", "Rumbi");
        body.put("lastName", "Moyo");
        body.put("phoneNumber", applicant.phone());
        body.put("email", applicant.email());
        body.put("country", "Zimbabwe");
        body.put("defaultServices", List.of("ticketing"));
        body.put("isBusiness", true);   // the wire name (@JsonProperty) of RegisterRequestDTO.business
        body.put("businessName", "Showtime Events");
        body.put("businessAddress", "5 Leopold Takawira St, Bulawayo");
        body.put("businessEmail", "hello-" + unique() + "@showtime-events.co.zw");
        body.put("bpoNumber", applicant.bpo());
        return mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** Registers through the real endpoint and returns the pending account. */
    private User registered(Applicant applicant) throws Exception {
        register(applicant).andExpect(status().isCreated());
        User user = users.findByEmail(applicant.email()).orElseThrow();
        assertThat(user.isApproved()).isFalse();
        return user;
    }

    private ResultActions reject(Long userId) throws Exception {
        return mockMvc.perform(put("/admin/users/{id}/reject", userId)
                .with(authentication(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", REASON))));
    }

    private ResultActions approve(Long userId) throws Exception {
        return mockMvc.perform(put("/admin/users/{id}/active", userId)
                .with(authentication(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private List<UUID> organizationsOf(Long userId) {
        return jdbc.queryForList("SELECT organization_id FROM organization_members WHERE user_id = ?", UUID.class, userId);
    }

    /** Every row a registration leaves behind, by table — all must be zero after a rejection. */
    private Map<String, Long> rowsOf(User user, Applicant applicant, List<UUID> organizationIds) {
        Map<String, Long> rows = new LinkedHashMap<>();
        rows.put("users", count("SELECT count(*) FROM users WHERE id = ?", user.getId()));
        rows.put("users by email", count("SELECT count(*) FROM users WHERE UPPER(email) = UPPER(?)", applicant.email()));
        rows.put("users by phone", count("SELECT count(*) FROM users WHERE phone_number = ?", applicant.phone()));
        rows.put("user_roles", count("SELECT count(*) FROM user_roles WHERE user_id = ?", user.getId()));
        rows.put("user_default_services",
                count("SELECT count(*) FROM user_default_services WHERE user_id = ?", user.getId()));
        rows.put("tenant_profiles", count("SELECT count(*) FROM tenant_profiles WHERE user_id = ?", user.getId()));
        rows.put("tenant_profiles by bpo",
                count("SELECT count(*) FROM tenant_profiles WHERE bpo_number = ?", applicant.bpo()));
        long organizations = 0;
        long members = 0;
        long products = 0;
        for (UUID organizationId : organizationIds) {
            organizations += count("SELECT count(*) FROM organizations WHERE id = ?", organizationId);
            members += count("SELECT count(*) FROM organization_members WHERE organization_id = ?", organizationId);
            products += count("SELECT count(*) FROM organization_products WHERE organization_id = ?", organizationId);
        }
        rows.put("organizations", organizations);
        rows.put("organization_members", members);
        rows.put("organization_members by user",
                count("SELECT count(*) FROM organization_members WHERE user_id = ?", user.getId()));
        rows.put("organization_products", products);
        rows.put("otps", count("SELECT count(*) FROM otps WHERE phone_number IN (?, ?)",
                applicant.email(), applicant.phone()));
        rows.put("refresh_tokens", count("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", user.getId()));
        rows.put("devices", count("SELECT count(*) FROM devices WHERE user_id = ?", user.getId()));
        rows.put("mfa_backup_codes", count("SELECT count(*) FROM mfa_backup_codes WHERE user_id = ?", user.getId()));
        rows.put("service_requests", count("SELECT count(*) FROM service_requests WHERE user_id = ?", user.getId()));
        return rows;
    }

    /**
     * The rows a pending registration CAN carry besides what registration
     * wrote: a live reset code for its email and phone (forgot-password works
     * for a pending account), and — written directly, since a pending account
     * cannot sign in to create them — a refresh token, a device, backup codes
     * and a service request, so every cascade and explicit delete is exercised.
     */
    private void plantDependents(User user, Applicant applicant) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (String key : List.of(applicant.email(), applicant.phone())) {
            jdbc.update("INSERT INTO otps (phone_number, code, expires_at, failed_attempts, created_at) "
                    + "VALUES (?, ?, ?, 0, ?)", key, "f".repeat(64), now.plusMinutes(5), now);
        }
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, expires_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), user.getId(),
                UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID(), now.plusDays(7), now);
        jdbc.update("INSERT INTO devices (user_id, device_id, registered_at) VALUES (?, ?, ?)",
                user.getId(), "portal-browser-" + unique(), now);
        jdbc.update("INSERT INTO mfa_backup_codes (user_id, code_hash, created_at) VALUES (?, ?, ?)",
                user.getId(), "$argon2id$placeholder-" + unique(), now);
        jdbc.update("INSERT INTO service_requests (user_id, service, reason, status, created_at) "
                + "VALUES (?, 'loyalty', 'We also run a loyalty scheme', 'PENDING', ?)", user.getId(), now);
    }

    /** An active business owned by somebody else, with the applicant as STAFF in it. */
    private UUID staffElsewhere(User applicant) {
        User otherOwner = users.save(User.builder()
                .firstName("Tendai").lastName("Ncube")
                .email("tendai-" + unique() + "@acme-merch.co.zw")
                .phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN))
                .active(true).approved(true)
                .build());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Organization other = organizationRepository.save(Organization.builder()
                .id(UUID.randomUUID()).name("Acme Merchandising").createdByUserId(otherOwner.getId())
                .createdAt(now).build());
        memberRepository.save(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(other.getId())
                .userId(otherOwner.getId()).role(OrganizationMember.Role.OWNER).createdAt(now).build());
        memberRepository.save(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(other.getId())
                .userId(applicant.getId()).role(OrganizationMember.Role.STAFF).createdAt(now).build());
        return other.getId();
    }

    @Test
    @DisplayName("rejecting removes every row of the registration, and the same details register again")
    void rejectRemovesEverything_thenTheSameDetailsRegisterAgain() throws Exception {
        Applicant applicant = freshApplicant();
        User user = registered(applicant);
        List<UUID> created = organizationsOf(user.getId());
        assertThat(created).as("registration creates the business").hasSize(1);
        plantDependents(user, applicant);
        UUID elsewhere = staffElsewhere(user);
        assertThat(rowsOf(user, applicant, created)).as("everything is there before").allSatisfy(
                (table, rows) -> assertThat(rows).as(table).isPositive());

        JsonNode data = data(reject(user.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200 OK"))
                .andExpect(jsonPath("$.message").value("Registration rejected")));
        assertThat(data.get("id").asLong()).isEqualTo(user.getId());
        assertThat(data.get("email").asText()).isEqualTo(applicant.email());
        assertThat(data.get("reason").asText()).isEqualTo(REASON);
        assertThat(data.get("rejectedAt").asText())
                .as("a human-facing timestamp, at the market offset")
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+02:00");

        assertThat(rowsOf(user, applicant, created)).as("nothing of the registration is left")
                .allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(count("SELECT count(*) FROM organizations WHERE id = ?", elsewhere))
                .as("somebody else's business stays").isOne();
        assertThat(count("SELECT count(*) FROM organization_members WHERE organization_id = ?", elsewhere))
                .as("only the applicant's membership in it went").isOne();

        assertThat(loyaltyCallsInTransaction).as("InnRewards asked once, with no transaction open")
                .containsExactly(false);
        verify(loyalty).merchantIdsForOrganizationIfKnown(created.get(0));

        // The audit chain is the only record left: actor, target, reason, and the
        // contact details masked.
        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_id, actor_type, target_type, outcome, metadata "
                + "FROM audit_events WHERE event_type = 'USER_REGISTRATION_REJECTED' AND target_id = ?",
                user.getUserUuid().toString());
        assertThat(audit).containsEntry("actor_id", ADMIN_EMAIL).containsEntry("actor_type", "USER")
                .containsEntry("target_type", "USER").containsEntry("outcome", "SUCCESS");
        JsonNode metadata = objectMapper.readTree((String) audit.get("metadata"));
        assertThat(metadata.get("reason").asText()).isEqualTo(REASON);
        assertThat(metadata.get("userId").asLong()).isEqualTo(user.getId());
        assertThat(metadata.get("organizationsDeleted").get(0).asText()).isEqualTo(created.get(0).toString());
        assertThat(metadata.get("membershipsRemoved").get(0).asText()).isEqualTo(elsewhere.toString());
        assertThat(metadata.get("businessName").asText()).isEqualTo("Showtime Events");
        assertThat(metadata.get("email").asText()).isEqualTo("o****@showtime-events.co.zw");
        assertThat(metadata.get("phone").asText()).isEqualTo("****" + applicant.phone().substring(applicant.phone().length() - 4));
        assertThat((String) audit.get("metadata")).doesNotContain(applicant.email()).doesNotContain(applicant.phone());

        // The applicant is told, after the commit.
        verify(dispatcher, timeout(10_000)).dispatch(applicant.email(), applicant.phone(),
                "Your InnBucks registration was not approved",
                "Hi Rumbi, your registration for Showtime Events on InnBucks was not approved.\n\n"
                        + "Reason: " + REASON + "\n\n"
                        + "You can register again once you have addressed this.");

        // Free: the same email, phone and BPO number register again.
        User again = registered(applicant);
        assertThat(again.getId()).isNotEqualTo(user.getId());
        assertThat(again.getUserUuid()).isNotEqualTo(user.getUserUuid());
    }

    @Test
    @DisplayName("approving a rejected registration is a 404: the account is gone")
    void approveAfterReject_isNotFound() throws Exception {
        User user = registered(freshApplicant());
        reject(user.getId()).andExpect(status().isOk());

        approve(user.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found: " + user.getId()));
        reject(user.getId()).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("rejecting an approved registration is a 409 registration_already_decided, and nothing is removed")
    void rejectAfterApprove_isAlreadyDecided() throws Exception {
        Applicant applicant = freshApplicant();
        User user = registered(applicant);
        List<UUID> created = organizationsOf(user.getId());
        approve(user.getId()).andExpect(status().isOk());

        reject(user.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("This account has already been approved. Deactivate it instead of rejecting it."))
                .andExpect(jsonPath("$.data.errorCode").value("registration_already_decided"));

        assertThat(rowsOf(user, applicant, created)).containsEntry("users", 1L).containsEntry("organizations", 1L)
                .containsEntry("tenant_profiles", 1L);
        verify(loyalty, never()).merchantIdsForOrganizationIfKnown(any());
    }

    @Test
    @DisplayName("approved between the InnRewards check and the row lock: 409 registration_changed, the approval stands")
    void approvedWhileBeingRejected_isChanged() throws Exception {
        Applicant applicant = freshApplicant();
        User user = registered(applicant);
        List<UUID> created = organizationsOf(user.getId());
        // The approval commits while the rejection is between its phases (the
        // InnRewards call, where no transaction is open). Under its row lock the
        // rejection then finds an approved account.
        when(loyalty.merchantIdsForOrganizationIfKnown(any())).thenAnswer(inv -> {
            userAdminService.setActive(user.getId(), true, ADMIN_EMAIL, AuditContext.none());
            return Optional.of(List.of());
        });

        reject(user.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("This registration changed while it was being rejected. Refresh and try again."))
                .andExpect(jsonPath("$.data.errorCode").value("registration_changed"));

        assertThat(users.findById(user.getId())).get().satisfies(u -> {
            assertThat(u.isApproved()).isTrue();
            assertThat(u.isActive()).isTrue();
        });
        assertThat(rowsOf(user, applicant, created)).containsEntry("organizations", 1L)
                .containsEntry("tenant_profiles", 1L);
        verify(dispatcher, after(1_000).never()).dispatch(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("InnRewards unknown (503) or holding a merchant (409): nothing is removed")
    void innRewardsRefusals_removeNothing() throws Exception {
        Applicant applicant = freshApplicant();
        User user = registered(applicant);
        List<UUID> created = organizationsOf(user.getId());
        Map<String, Long> before = rowsOf(user, applicant, created);

        when(loyalty.merchantIdsForOrganizationIfKnown(any())).thenReturn(Optional.empty());
        reject(user.getId())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(
                        "We couldn't confirm this business isn't already set up in InnRewards. Try again in a minute."))
                .andExpect(jsonPath("$.data.errorCode").value("registration_check_unavailable"));
        assertThat(rowsOf(user, applicant, created)).isEqualTo(before);

        when(loyalty.merchantIdsForOrganizationIfKnown(any())).thenReturn(Optional.of(List.of(UUID.randomUUID())));
        reject(user.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("registration_business_in_use"))
                .andExpect(jsonPath("$.data.reason").value("loyalty_merchant"));
        assertThat(rowsOf(user, applicant, created)).isEqualTo(before);
    }

    @Test
    @DisplayName("Postgres refuses the audit row: 503 audit_unavailable, nothing removed, nobody told")
    void auditFailure_removesNothing_andTellsNobody() throws Exception {
        Applicant applicant = freshApplicant();
        User user = registered(applicant);
        List<UUID> created = organizationsOf(user.getId());
        plantDependents(user, applicant);
        Map<String, Long> before = rowsOf(user, applicant, created);
        jdbc.execute("CREATE OR REPLACE FUNCTION it_fail_rejection_audit() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.event_type = 'USER_REGISTRATION_REJECTED' AND NEW.target_id = '" + user.getUserUuid()
                + "' THEN RAISE EXCEPTION 'simulated audit outage'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER it_fail_rejection_audit BEFORE INSERT ON audit_events "
                + "FOR EACH ROW EXECUTE FUNCTION it_fail_rejection_audit()");

        reject(user.getId())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("We couldn't record this change, so it wasn't made. Try again."))
                .andExpect(jsonPath("$.data.errorCode").value("audit_unavailable"));

        assertThat(rowsOf(user, applicant, created)).as("the deletes rolled back with the audit").isEqualTo(before);
        verify(dispatcher, after(1_000).never()).dispatch(anyString(), anyString(), anyString(), anyString());

        // The control: once the audit can be written, the identical request goes through.
        dropTheAuditTrigger();
        reject(user.getId()).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM users WHERE id = ?", user.getId())).isZero();
    }
}
