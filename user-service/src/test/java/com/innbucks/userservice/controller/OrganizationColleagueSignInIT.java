package com.innbucks.userservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.dto.OrganizationDTOs;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.OrganizationProduct;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An ADMIN colleague signing into the admin portal, start to finish, through
 * the real endpoints: password login, the forced MFA enrolment, and (for a
 * colleague who also runs a business of their own) the organization pick.
 *
 * <p>This is the account step 2 of the organizations plan exists for: a person
 * a business added through {@code POST /organizations/{id}/members} who holds
 * NO platform role at all — here a super-app customer. Loyalty and the
 * marketplace make them a merchant admin from the organization claims alone
 * (InnRewards {@code MerchantOrganizationOwnershipSecurityTest} and
 * market-place {@code SecuritySurfaceIT}, the {@code anAdminColleague_*}
 * cases, both sign tokens with exactly {@code roles: [CUSTOMER]} plus
 * {@code orgRole: ADMIN} and the product). What neither can prove is that
 * user-service actually mints that token for such a person, which is what this
 * test pins.
 *
 * <p>Two things about the flow a client must expect, both asserted below:
 * <ul>
 *   <li>Belonging to an organization makes the account a system user, so the
 *       first password login is {@code mfaEnrollmentRequired}, never a token —
 *       even though the account itself is only a CUSTOMER.</li>
 *   <li>A colleague who owns a business of their own belongs to two, so no
 *       organization is chosen for them: {@code organizationSelectionRequired}
 *       until they pick one through {@code POST /auth/organization-context}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationColleagueSignInIT {

    private static final String PASSWORD = "Colleague-Login-7q";
    private static final String DEVICE = "portal-browser-1";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMemberRepository members;
    @Autowired OrganizationProductRepository products;
    @Autowired OrganizationService organizationService;
    @Autowired CustomerProfileRepository customerProfiles;
    @Autowired PasswordEncoder passwordEncoder;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String phone() {
        return "+26377" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_999_999));
    }

    private User account(String email, String role) {
        return users.save(User.builder()
                .firstName("Tendai").lastName("Moyo")
                .email(email)
                .phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.valueOf(role)))
                .active(true).approved(true)
                .build());
    }

    /**
     * A super-app customer who reached tier 2 — the only way an account with no
     * platform role carries the email {@code POST /organizations/{id}/members}
     * adds people by. Every real customer has this profile row from
     * registration, and issuing any CUSTOMER session reads it.
     */
    private User customer(String email) {
        User user = account(email, "CUSTOMER");
        customerProfiles.save(CustomerProfile.builder()
                .user(user).registrationTier(2).fullName("Tendai Moyo").phoneVerified(true).build());
        return user;
    }

    /** A business, owned by {@code owner}, holding the given ACTIVE products. */
    private Organization business(String name, User owner, String... productCodes) {
        Organization org = organizations.save(Organization.builder().id(UUID.randomUUID()).name(name).build());
        members.save(OrganizationMember.builder().id(UUID.randomUUID())
                .organizationId(org.getId()).userId(owner.getId()).role(OrganizationMember.Role.OWNER).build());
        for (String code : productCodes) {
            products.save(OrganizationProduct.builder().id(UUID.randomUUID())
                    .organizationId(org.getId()).product(code).status(OrganizationProduct.Status.ACTIVE).build());
        }
        return org;
    }

    /** The owner adds the colleague as ADMIN — the service the members endpoint calls. */
    private void addAsAdmin(User owner, Organization org, User colleague) {
        OrganizationDTOs.AddMemberRequest request = new OrganizationDTOs.AddMemberRequest();
        request.setEmail(colleague.getEmail());
        request.setRole(OrganizationMember.Role.ADMIN);
        organizationService.addMember(owner, org.getId(), request);
    }

    /** Password login → forced enrolment → enroll/complete, as the portal does it. */
    private JsonNode signInEnrolling(String email) throws Exception {
        String login = mockMvc.perform(post("/auth/login")
                        .header("X-Device-Id", DEVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                // A plain CUSTOMER is never challenged — this is the organization talking.
                .andExpect(jsonPath("$.data.mfaEnrollmentRequired").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String mfaToken = objectMapper.readTree(login).at("/data/mfaToken").asText();

        String start = mockMvc.perform(post("/auth/mfa/enroll/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaToken", mfaToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secret = objectMapper.readTree(start).at("/data/secret").asText();
        String echoed = objectMapper.readTree(start).at("/data/mfaToken").asText();

        String complete = mockMvc.perform(post("/auth/mfa/enroll/complete")
                        .header("X-Device-Id", DEVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaToken", echoed, "code", totp(secret)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(complete).at("/data");
    }

    private static String totp(String secret) {
        try {
            long step = new dev.samstevens.totp.time.SystemTimeProvider().getTime() / 30;
            return new dev.samstevens.totp.code.DefaultCodeGenerator().generate(secret, step);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TOTP for test", e);
        }
    }

    /** The token's claims, read the way a consumer sees them. */
    private JsonNode claims(String jwt) throws Exception {
        String payload = jwt.split("\\.")[1];
        return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    private static List<String> strings(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    @Test
    void anAdminColleagueWithNoPlatformRole_signsIn_andActsForTheBusiness() throws Exception {
        String t = unique();
        User owner = account("owner-" + t + "@acme.test", "MERCHANT_ADMIN");
        Organization acme = business("Acme " + t, owner, "loyalty", "marketplace");
        User colleague = customer("colleague-" + t + "@acme.test");
        addAsAdmin(owner, acme, colleague);

        JsonNode session = signInEnrolling(colleague.getEmail());

        // The response carries the scope and the products, so the portal can
        // gate its menus without decoding the token.
        assertThat(session.at("/organizationId").asText()).isEqualTo(acme.getId().toString());
        assertThat(session.at("/organizationRole").asText()).isEqualTo("ADMIN");
        assertThat(strings(session.at("/organizationProducts"))).containsExactly("loyalty", "marketplace");
        assertThat(session.at("/organizationSelectionRequired").isMissingNode()).isTrue();

        // …and the token is exactly the shape loyalty and the marketplace
        // accept as a merchant admin: no staff role, the organization claims,
        // and no merchantId claim for anyone to fall back on.
        JsonNode token = claims(session.at("/token").asText());
        assertThat(strings(token.at("/roles"))).containsExactly("CUSTOMER");
        assertThat(token.at("/orgId").asText()).isEqualTo(acme.getId().toString());
        assertThat(token.at("/orgRole").asText()).isEqualTo("ADMIN");
        assertThat(strings(token.at("/products"))).containsExactly("loyalty", "marketplace");
        assertThat(token.has("merchantId")).isFalse();
    }

    @Test
    void aColleagueWhoRunsABusinessOfTheirOwn_picksWhichOneToActFor() throws Exception {
        String t = unique();
        User owner = account("owner-" + t + "@acme.test", "MERCHANT_ADMIN");
        Organization acme = business("Acme " + t, owner, "loyalty", "marketplace");
        // Self-registration always creates the registrant's own business, so a
        // colleague who signed up the ordinary way belongs to two once added.
        User colleague = account("colleague-" + t + "@own-shop.test", "MERCHANT_ADMIN");
        business("Own Shop " + t, colleague, "marketplace");
        addAsAdmin(owner, acme, colleague);

        JsonNode session = signInEnrolling(colleague.getEmail());

        assertThat(session.at("/organizationSelectionRequired").asBoolean()).isTrue();
        assertThat(session.at("/organizationId").isMissingNode()).isTrue();
        assertThat(claims(session.at("/token").asText()).has("orgId")).isFalse();

        String picked = mockMvc.perform(post("/auth/organization-context")
                        .header("Authorization", "Bearer " + session.at("/refreshToken").asText())
                        .header("X-Device-Id", DEVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("organizationId", acme.getId().toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode chosen = objectMapper.readTree(picked).at("/data");

        assertThat(chosen.at("/organizationId").asText()).isEqualTo(acme.getId().toString());
        assertThat(chosen.at("/organizationRole").asText()).isEqualTo("ADMIN");
        assertThat(strings(chosen.at("/organizationProducts"))).containsExactly("loyalty", "marketplace");
        JsonNode token = claims(chosen.at("/token").asText());
        assertThat(token.at("/orgRole").asText()).isEqualTo("ADMIN");
        assertThat(strings(token.at("/products"))).containsExactly("loyalty", "marketplace");
    }
}
