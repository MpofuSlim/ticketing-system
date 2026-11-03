package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.OrganizationProduct;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.OrganizationException;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.PosApiKeyRepository;
import com.innbucks.userservice.security.ServiceSuspensionFilter;
import com.innbucks.userservice.service.OrganizationService;
import com.innbucks.userservice.testsupport.StaffItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static com.innbucks.userservice.controller.AdminServiceSuspensionController.EXAMPLE_ALREADY_SUSPENDED;
import static com.innbucks.userservice.controller.AdminServiceSuspensionController.EXAMPLE_EVENTS_ON_SALE;
import static com.innbucks.userservice.controller.AdminServiceSuspensionController.EXAMPLE_NOT_SUSPENDED;
import static com.innbucks.userservice.controller.AdminServiceSuspensionController.EXAMPLE_UNKNOWN_PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suspending and reinstating one service of a business (V53), end to end
 * against real Postgres and the real sign-in: what a suspension reaches (the
 * owners' organizer role, a merchant's cashiers and till keys), the refusals
 * compared strictly with their Swagger examples, and a reinstate that gives it
 * all back. InnRewards and event-service are the only mocks.
 */
class ServiceSuspensionIT extends StaffItSupport {

    @MockitoBean LoyaltyServiceClient loyalty;
    @MockitoBean EventServiceClient events;

    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMemberRepository members;
    @Autowired OrganizationProductRepository products;
    @Autowired PosApiKeyRepository keys;
    @Autowired ServiceSuspensionFilter suspensionFilter;
    @Autowired OrganizationService organizationService;

    private static UsernamePasswordAuthenticationToken superAdmin() {
        return new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, null, List.of(
                new SimpleGrantedAuthority("organizations:manage"), new SimpleGrantedAuthority("organizations:read"),
                new SimpleGrantedAuthority("pos-keys:manage"), new SimpleGrantedAuthority("pos-keys:read")));
    }

    private User owner(User.Role... roles) {
        return users.save(User.builder().firstName("Rudo").lastName("Moyo")
                .email("rudo-" + unique() + "@moyo-fresh.example").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD)).roles(User.roleNames(roles))
                .active(true).approved(true).mfaEnabled(true).mfaSecret(TOTP_SECRET).build());
    }

    private Organization business(User owner, String product) {
        Organization org = organizations.save(Organization.builder().id(UUID.randomUUID())
                .name("Moyo Fresh Foods " + unique()).build());
        members.save(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(org.getId())
                .userId(owner.getId()).role(OrganizationMember.Role.OWNER).build());
        products.save(OrganizationProduct.builder().id(UUID.randomUUID()).organizationId(org.getId())
                .product(product).status(OrganizationProduct.Status.ACTIVE).build());
        return org;
    }

    private ResultActions suspend(UUID org, String product) throws Exception {
        return mockMvc.perform(post("/admin/organizations/{id}/products/{p}/suspend", org, product)
                .with(authentication(superAdmin())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Approved for this business by mistake (OPS-1204).\"}"));
    }

    private ResultActions reinstate(UUID org, String product) throws Exception {
        return mockMvc.perform(post("/admin/organizations/{id}/products/{p}/reinstate", org, product)
                .with(authentication(superAdmin())));
    }

    private ResultActions exchange(String key) throws Exception {
        return mockMvc.perform(post("/auth/pos/token").header("X-Api-Key", key));
    }

    private JsonNode refresh(JsonNode session) throws Exception {
        return data(mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + session.at("/refreshToken").asText())
                        .header("X-Device-Id", DEVICE))
                .andExpect(status().isOk()));
    }

    @Test
    void loyalty_stopsTheMerchantsCashiersAndTillKeys_untilReinstated() throws Exception {
        User owner = owner(User.Role.MERCHANT_ADMIN);
        Organization org = business(owner, "loyalty");
        UUID merchant = UUID.randomUUID();
        User cashier = users.save(User.builder().firstName("Tendai").lastName("Dube")
                .email("tendai-" + unique() + "@moyo-fresh.example").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD)).roles(User.roleNames(User.Role.SHOP_USER))
                .loyaltyMerchantId(merchant).loyaltyShopId(UUID.randomUUID()).active(true).approved(true).build());
        String key = data(mockMvc.perform(post("/admin/pos-keys").with(authentication(superAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Moyo till\",\"merchantId\":\"%s\"}".formatted(merchant)))
                .andExpect(status().isCreated())).at("/key").asText();
        UUID keyId = keys.findAll().stream().filter(k -> merchant.equals(k.getMerchantId())).findFirst()
                .orElseThrow().getId();
        exchange(key).andExpect(status().isOk());
        long cashierBefore = liveTokenVersion(cashier.getId());
        long keyBefore = keys.findById(keyId).orElseThrow().getTokenVersion();
        when(loyalty.merchantIdsForOrganizationIfKnown(org.getId())).thenReturn(Optional.of(List.of(merchant)));

        suspend(org.getId(), "loyalty")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Service suspended"))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.membersSignedOut").value(1))
                .andExpect(jsonPath("$.data.cashiersSignedOut").value(1))
                .andExpect(jsonPath("$.data.tillKeysStopped").value(1));

        assertThat(liveTokenVersion(cashier.getId())).isEqualTo(cashierBefore + 1);
        assertThat(keys.findById(keyId).orElseThrow().getTokenVersion()).isEqualTo(keyBefore + 1);
        assertThat(count("SELECT count(*) FROM suspended_loyalty_merchants WHERE organization_id = ?1",
                org.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'ORGANIZATION_PRODUCT_SUSPENDED' "
                + "AND target_id = ?1", org.getId().toString())).isEqualTo(1);
        exchange(key).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("pos_key_rejected"));
        User reloaded = users.findById(cashier.getId()).orElseThrow();
        assertThat(suspensionFilter.withholdSuspended(reloaded, List.of("SHOP_USER"))).isEmpty();
        mockMvc.perform(get("/admin/organizations").param("q", org.getName()).with(authentication(superAdmin())))
                .andExpect(jsonPath("$.data.content[0].products").isEmpty())
                .andExpect(jsonPath("$.data.content[0].suspendedProducts[0]").value("loyalty"));
        suspend(org.getId(), "loyalty").andExpect(status().isConflict())
                .andExpect(content().json(EXAMPLE_ALREADY_SUSPENDED, JsonCompareMode.STRICT));
        // Approving another loyalty request does not bring it back.
        assertThatThrownBy(() -> organizationService.grantProduct(owner, org.getId(), "loyalty", owner))
                .isInstanceOfSatisfying(OrganizationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("product_suspended"));

        reinstate(org.getId(), "loyalty")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.reason").value(nullValue()));
        assertThat(count("SELECT count(*) FROM suspended_loyalty_merchants WHERE organization_id = ?1",
                org.getId())).isZero();
        exchange(key).andExpect(status().isOk());
        assertThat(suspensionFilter.withholdSuspended(reloaded, List.of("SHOP_USER"))).containsExactly("SHOP_USER");
        reinstate(org.getId(), "loyalty").andExpect(status().isConflict())
                .andExpect(content().json(EXAMPLE_NOT_SUSPENDED, JsonCompareMode.STRICT));
    }

    @Test
    void ticketing_isRefusedWhileEventsAreOnSale_thenWithdrawsTheOrganizerRoleUntilReinstated() throws Exception {
        User owner = owner(User.Role.EVENT_ORGANIZER);
        Organization org = business(owner, "ticketing");
        JsonNode session = signIn(owner.getEmail());
        assertThat(strings(claims(session.at("/token").asText()).path("roles"))).contains("EVENT_ORGANIZER");

        when(events.liveEventCount(anyCollection())).thenReturn(OptionalLong.of(2));
        suspend(org.getId(), "ticketing").andExpect(status().isConflict())
                .andExpect(content().json(EXAMPLE_EVENTS_ON_SALE, JsonCompareMode.STRICT));

        when(events.liveEventCount(anyCollection())).thenReturn(OptionalLong.of(0));
        suspend(org.getId(), "ticketing").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membersSignedOut").value(1));
        JsonNode suspended = claims(refresh(session).at("/token").asText());
        assertThat(strings(suspended.path("roles"))).doesNotContain("EVENT_ORGANIZER");
        assertThat(suspended.has("organizerUuid")).isFalse();

        reinstate(org.getId(), "ticketing").andExpect(status().isOk());
        JsonNode back = claims(refresh(session).at("/token").asText());
        assertThat(strings(back.path("roles"))).contains("EVENT_ORGANIZER");
        assertThat(back.path("organizerUuid").asText()).isEqualTo(owner.getUserUuid().toString());
    }

    @Test
    void anUnknownProduct_isA400() throws Exception {
        User owner = owner(User.Role.MERCHANT_ADMIN);
        Organization org = business(owner, "marketplace");

        suspend(org.getId(), "payroll").andExpect(status().isBadRequest())
                .andExpect(content().json(EXAMPLE_UNKNOWN_PRODUCT, JsonCompareMode.STRICT));
    }
}
