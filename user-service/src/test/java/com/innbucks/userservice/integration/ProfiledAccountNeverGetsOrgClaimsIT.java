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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The mint-time backstop for organizations (V44 §2.9): a staff account's token
 * NEVER carries {@code orgId} / {@code orgRole} / {@code products}, whatever
 * {@code organization_members} holds — so a membership row written by a path the
 * staff rules missed (direct SQL, a future writer) cannot make a support agent a
 * merchant admin in loyalty or a seller in the marketplace, both of which grant
 * authority from those claims alone.
 */
class ProfiledAccountNeverGetsOrgClaimsIT extends StaffItSupport {

    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMemberRepository members;
    @Autowired OrganizationProductRepository products;

    private Organization businessOwnedBy(User owner, String name) {
        Organization org = organizations.save(Organization.builder().id(UUID.randomUUID()).name(name).build());
        // Written directly: the path the staff rules refuse is exactly what this backstop is for.
        members.save(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(org.getId())
                .userId(owner.getId()).role(OrganizationMember.Role.OWNER).build());
        products.save(OrganizationProduct.builder().id(UUID.randomUUID()).organizationId(org.getId())
                .product("loyalty").status(OrganizationProduct.Status.ACTIVE).build());
        return org;
    }

    @Test
    void aStaffAccountWithIllegalMemberships_getsNoOrganizationClaims() throws Exception {
        User agent = eligibleStaff("CALL_CENTER_AGENT", true);
        Organization first = businessOwnedBy(agent, "Chikwanha Traders " + unique());
        businessOwnedBy(agent, "Second Business " + unique());

        // Two ACTIVE memberships would normally mean "pick one" — not for staff.
        JsonNode session = signIn(agent.getEmail());
        JsonNode claims = claims(session.at("/token").asText());
        assertThat(claims.has("orgId")).isFalse();
        assertThat(claims.has("orgRole")).isFalse();
        assertThat(claims.has("products")).isFalse();
        assertThat(session.path("organizationId").isMissingNode() || session.path("organizationId").isNull()).isTrue();
        assertThat(session.path("organizationSelectionRequired").isMissingNode()
                || session.path("organizationSelectionRequired").isNull()).isTrue();
        assertThat(strings(claims.get("roles"))).containsExactly("CALL_CENTER_AGENT");

        // Explicitly choosing one does not attach it either.
        JsonNode switched = data(mockMvc.perform(post("/auth/organization-context")
                        .header("Authorization", "Bearer " + session.at("/refreshToken").asText())
                        .header("X-Device-Id", DEVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + first.getId() + "\"}"))
                .andExpect(status().isOk()));
        assertThat(claims(switched.at("/token").asText()).has("orgId")).isFalse();

        // Control: the same memberships on a business account DO produce the claims.
        User merchant = users.save(User.builder().firstName("Rudo").lastName("Chikwanha")
                .email("rudo-" + unique() + "@chikwanha-traders.co.zw").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD)).roles(User.roleNames(User.Role.MERCHANT_ADMIN))
                .active(true).approved(true).mfaEnabled(true).mfaSecret(TOTP_SECRET).build());
        businessOwnedBy(merchant, "Control Business " + unique());
        JsonNode control = claims(signIn(merchant.getEmail()).at("/token").asText());
        assertThat(control.has("orgId")).isTrue();
        assertThat(strings(control.get("products"))).containsExactly("loyalty");
    }
}
