package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.OrganizationProduct;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.WithSuperAdmin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /admin/organizations} — the one list of EVERY business, which an
 * operator needs now that loyalty merchants and marketplace listings are owned
 * by an organization id. Runs against the real schema because the filters are
 * Criteria predicates (an EXISTS subquery, a LIKE with an escape character)
 * that a mocked repository would never execute.
 *
 * <p>The context and database are shared with every other test class, so each
 * case seeds organizations under a unique name token and filters by it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminOrganizationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMemberRepository members;
    @Autowired OrganizationProductRepository products;
    @Autowired UserRepository users;

    private static String token() {
        return "Dir" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Organization organization(String name) {
        return organizations.save(Organization.builder().id(UUID.randomUUID()).name(name).build());
    }

    private void product(Organization o, String product, OrganizationProduct.Status status) {
        products.save(OrganizationProduct.builder().id(UUID.randomUUID())
                .organizationId(o.getId()).product(product).status(status).build());
    }

    private void member(Organization o, String email, OrganizationMember.Role role) {
        String digits = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits())).substring(0, 9);
        User u = users.save(User.builder()
                .firstName("Dir").lastName("Member")
                .email(email)
                .phoneNumber("+263" + digits)
                .password("{noop}unused")
                .roles(User.roleNames(User.Role.CUSTOMER))
                .active(true)
                .build());
        members.save(OrganizationMember.builder().id(UUID.randomUUID())
                .organizationId(o.getId()).userId(u.getId()).role(role).build());
    }

    @Test
    @WithSuperAdmin
    void superAdmin_listsOrganizations_withActiveProductsAndOwnerEmailsOnly() throws Exception {
        String t = token();
        Organization acme = organization(t + " Acme");
        product(acme, "marketplace", OrganizationProduct.Status.ACTIVE);
        product(acme, "loyalty", OrganizationProduct.Status.ACTIVE);
        product(acme, "ticketing", OrganizationProduct.Status.SUSPENDED);
        member(acme, t.toLowerCase() + "-owner@acme.test", OrganizationMember.Role.OWNER);
        member(acme, t.toLowerCase() + "-admin@acme.test", OrganizationMember.Role.ADMIN);

        mockMvc.perform(get("/admin/organizations").param("q", t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200 OK"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].organizationId").value(acme.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"))
                // Sorted, active only — a suspended product is not something they can use.
                .andExpect(jsonPath("$.data.content[0].products", contains("loyalty", "marketplace")))
                // OWNERs only: the column exists to tell businesses apart by who runs them.
                .andExpect(jsonPath("$.data.content[0].ownerEmails", contains(t.toLowerCase() + "-owner@acme.test")));
    }

    @Test
    @WithSuperAdmin
    void productFilter_keepsOnlyOrganizationsHoldingItActive() throws Exception {
        String t = token();
        Organization seller = organization(t + " Seller");
        product(seller, "marketplace", OrganizationProduct.Status.ACTIVE);
        Organization suspended = organization(t + " Suspended Seller");
        product(suspended, "marketplace", OrganizationProduct.Status.SUSPENDED);
        Organization loyaltyOnly = organization(t + " Loyalty Only");
        product(loyaltyOnly, "loyalty", OrganizationProduct.Status.ACTIVE);

        mockMvc.perform(get("/admin/organizations").param("q", t).param("product", "Marketplace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[*].organizationId", contains(seller.getId().toString())));
    }

    @Test
    @WithSuperAdmin
    void anUnknownProduct_isRefused_ratherThanReturningAnEmptyPage() throws Exception {
        mockMvc.perform(get("/admin/organizations").param("product", "lottery"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("unknown_product"))
                .andExpect(jsonPath("$.message").value("Unknown product. Use one of: ticketing, loyalty, marketplace."));
    }

    @Test
    @WithSuperAdmin
    void theSearchTerm_isMatchedLiterally_soAPercentSignIsNotAWildcard() throws Exception {
        String t = token();
        Organization literal = organization(t + " 100% Organic");
        organization(t + " 1000 Organics");

        mockMvc.perform(get("/admin/organizations").param("q", t.toLowerCase() + " 100%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].organizationId", contains(literal.getId().toString())));
    }

    @Test
    @WithSuperAdmin
    void twoBusinessesWithOneName_bothAppearAcrossPages_neverRepeated() throws Exception {
        String t = token();
        Organization first = organization(t + " Twin");
        Organization second = organization(t + " Twin");

        String page0 = mockMvc.perform(get("/admin/organizations").param("q", t).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();
        String firstId = com.jayway.jsonpath.JsonPath.read(page0, "$.data.content[0].organizationId");

        mockMvc.perform(get("/admin/organizations").param("q", t).param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[*].organizationId", not(hasItem(firstId))))
                .andExpect(jsonPath("$.data.content[*].organizationId",
                        contains(firstId.equals(first.getId().toString())
                                ? second.getId().toString() : first.getId().toString())));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_MERCHANT_ADMIN", "shop-staff:read"})
    void aMerchantAdmin_cannotListEveryBusiness() throws Exception {
        mockMvc.perform(get("/admin/organizations"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousCaller_isUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/organizations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithSuperAdmin
    void noFilters_listsEveryOrganization() throws Exception {
        organization(token() + " Alpha");
        organization(token() + " Beta");

        // No q and no product bind no parameter at all — nothing null reaches SQL.
        mockMvc.perform(get("/admin/organizations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements", greaterThanOrEqualTo(2)));
    }
}
