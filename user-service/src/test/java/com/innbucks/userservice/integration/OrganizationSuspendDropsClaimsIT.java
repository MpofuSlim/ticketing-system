package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.OrganizationProduct;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.testsupport.StaffItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /admin/organizations/{id}/suspend} (V44, {@code organizations:manage}):
 * the organization stops being ACTIVE, every member's access token dies at once
 * (their tokenVersion is bumped), and their NEXT token carries no organization
 * claims — so loyalty and the marketplace, which grant authority from those
 * claims alone, stop treating them as that business immediately. It is also how
 * an operator unblocks adoption of a legacy "staff" account that owns an
 * organization the console created for it.
 */
class OrganizationSuspendDropsClaimsIT extends StaffItSupport {

    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMemberRepository members;
    @Autowired OrganizationProductRepository products;

    private Organization ownedBy(User owner) {
        Organization org = organizations.save(Organization.builder().id(UUID.randomUUID())
                .name("Business " + unique()).build());
        members.save(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(org.getId())
                .userId(owner.getId()).role(OrganizationMember.Role.OWNER).build());
        products.save(OrganizationProduct.builder().id(UUID.randomUUID()).organizationId(org.getId())
                .product("loyalty").status(OrganizationProduct.Status.ACTIVE).build());
        return org;
    }

    private org.springframework.test.web.servlet.ResultActions suspend(UUID orgId, String note) throws Exception {
        return mockMvc.perform(post("/admin/organizations/{id}/suspend", orgId).with(authentication(staffAdmin()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("note", note))));
    }

    @Test
    void suspendingEndsTheMembersSessions_andTheirNextTokenHasNoOrganization() throws Exception {
        User merchant = users.save(User.builder().firstName("Rudo").lastName("Chikwanha")
                .email("rudo-" + unique() + "@chikwanha-traders.co.zw").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD)).roles(User.roleNames(User.Role.MERCHANT_ADMIN))
                .active(true).approved(true).mfaEnabled(true).mfaSecret(TOTP_SECRET).build());
        Organization org = ownedBy(merchant);
        JsonNode session = signIn(merchant.getEmail());
        assertThat(claims(session.at("/token").asText()).path("orgId").asText()).isEqualTo(org.getId().toString());
        long before = liveTokenVersion(merchant.getId());

        suspend(org.getId(), "Duplicate business created by mistake (OPS-1187).")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.membersSignedOut").value(1));
        assertThat(liveTokenVersion(merchant.getId())).isEqualTo(before + 1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'ORGANIZATION_SUSPENDED' "
                + "AND target_id = ?1", org.getId().toString())).isEqualTo(1);

        // The old access token is dead at once...
        mockMvc.perform(get("/notifications/unread-count")
                        .header("Authorization", "Bearer " + session.at("/token").asText()))
                .andExpect(status().isUnauthorized());
        // ...and the next one carries no organization.
        JsonNode refreshed = data(mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + session.at("/refreshToken").asText())
                        .header("X-Device-Id", DEVICE))
                .andExpect(status().isOk()));
        assertThat(claims(refreshed.at("/token").asText()).has("orgId")).isFalse();

        // Suspending twice is refused, not repeated.
        suspend(org.getId(), "again")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("organization_not_active"));
    }

    @Test
    void suspendingTheOrganizationALegacyStaffAccountOwns_unblocksItsAdoption() throws Exception {
        User legacy = staff("PRODUCT_OFFICER", true);
        Organization org = ownedBy(legacy);

        mockMvc.perform(post("/admin/staff/{id}/resend-invite", legacy.getId()).with(authentication(staffAdmin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("adoption_blocked"))
                .andExpect(jsonPath("$.data.reason").value("organization_member"));

        suspend(org.getId(), "Console-created for a staff member (OPS-1190).").andExpect(status().isOk());

        mockMvc.perform(post("/admin/staff/{id}/resend-invite", legacy.getId()).with(authentication(staffAdmin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVITED"));
    }
}
