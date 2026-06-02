package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantProfileRepository tenantProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void testListUsersReturnsDefaultServicesAsBundle() throws Exception {
        // Create a test user with ticketing and loyalty bundles
        User testUser = User.builder()
                .firstName("Test")
                .lastName("User")
                .email("test-bundles@example.com")
                .phoneNumber("+1234567890")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.EVENT_ORGANIZER, User.Role.MERCHANT_ADMIN))
                .defaultServices(new LinkedHashSet<>(List.of("ticketing", "loyalty")))
                .active(true)
                .build();
        userRepository.save(testUser);

        MvcResult result = mockMvc.perform(get("/admin/users")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("\"defaultServices\":[\"ticketing\",\"loyalty\"]");

        // Verify NOT the expanded microservices
        assertThat(responseBody).doesNotContain("\"events\"");
        assertThat(responseBody).doesNotContain("\"seats\"");
        assertThat(responseBody).doesNotContain("\"bookings\"");
        assertThat(responseBody).doesNotContain("\"payments\"");
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void testListUsersWithActiveFilterReturnsCorrectBundles() throws Exception {
        // Create an inactive user with ticketing bundle only
        User inactiveUser = User.builder()
                .firstName("Inactive")
                .lastName("User")
                .email("inactive-user@example.com")
                .phoneNumber("+0987654321")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.EVENT_ORGANIZER))
                .defaultServices(new LinkedHashSet<>(List.of("ticketing")))
                .active(false)
                .build();
        userRepository.save(inactiveUser);

        MvcResult result = mockMvc.perform(get("/admin/users?active=false")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        // Should contain ticketing bundle name, not expanded microservices
        assertThat(responseBody).contains("\"defaultServices\":[\"ticketing\"]");
        assertThat(responseBody).doesNotContain("\"events\"");
        assertThat(responseBody).doesNotContain("\"seats\"");
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void listUsersExcludesCustomersByDefault() throws Exception {
        // The SUPER_ADMIN portal lists system users (admins / staff); the
        // wallet-holding customer population belongs on the customer
        // surface and would drown the page if mixed in. Seed one of each
        // class and assert only the system user comes back.
        User systemUser = User.builder()
                .firstName("Sysadmin")
                .lastName("One")
                .email("sysadmin-default@example.com")
                .phoneNumber("+260000000001")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .active(true)
                .build();
        User customer = User.builder()
                .firstName("Customer")
                .lastName("One")
                .email("customer-default@example.com")
                .phoneNumber("+260000000002")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.CUSTOMER))
                .active(true)
                .build();
        userRepository.save(systemUser);
        userRepository.save(customer);

        MvcResult result = mockMvc.perform(get("/admin/users")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("sysadmin-default@example.com");
        assertThat(body).doesNotContain("customer-default@example.com");
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void listUsersIncludesCustomersWhenOptedIn() throws Exception {
        // ?includeCustomers=true is the escape hatch for support triage —
        // it brings the customer population back into the result set.
        User customer = User.builder()
                .firstName("Customer")
                .lastName("Two")
                .email("customer-optin@example.com")
                .phoneNumber("+260000000003")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.CUSTOMER))
                .active(true)
                .build();
        userRepository.save(customer);

        MvcResult result = mockMvc.perform(get("/admin/users?includeCustomers=true")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("customer-optin@example.com");
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void testUpdateActiveStatusReturnsDefaultServicesAsBundle() throws Exception {
        // Create a test user with loyalty bundle
        User loyaltyUser = User.builder()
                .firstName("Loyalty")
                .lastName("Organizer")
                .email("loyalty-org@example.com")
                .phoneNumber("+1111111111")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .defaultServices(new LinkedHashSet<>(List.of("loyalty")))
                .active(false)
                .build();
        loyaltyUser = userRepository.save(loyaltyUser);

        MvcResult result = mockMvc.perform(put("/admin/users/" + loyaltyUser.getId() + "/active")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\": true}"))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        // Should contain loyalty bundle name, not expanded microservices
        assertThat(responseBody).contains("\"defaultServices\":[\"loyalty\"]");
        assertThat(responseBody).doesNotContain("\"payments\"");
    }

    // ---- GET /admin/users/merchants (SUPER_ADMIN-only MERCHANT_ADMIN + EVENT_ORGANIZER listing) ----

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void listMerchants_returnsMerchantAdminsAndEventOrganizers() throws Exception {
        User merchant = User.builder()
                .firstName("Tendai").lastName("Ncube")
                .email("tendai@acme-merch.co.zw").phoneNumber("+263772345678")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .defaultServices(new LinkedHashSet<>(List.of("loyalty")))
                .active(true)
                .business(true)
                .build();
        userRepository.save(merchant);

        // Business account -> tenant profile must be surfaced as businessDetails.
        TenantProfile merchantProfile = TenantProfile.builder()
                .user(merchant)
                .businessName("Acme Merchandising (Pvt) Ltd")
                .businessAddress("12 Samora Machel Ave, Harare")
                .businessEmail("accounts@acme-merch.co.zw")
                .businessPhoneNumber("+263242123456")
                .registrationNumber("CR-2026-00891")
                .bpoNumber("BPO-44512")
                .build();
        tenantProfileRepository.save(merchantProfile);

        User organizer = User.builder()
                .firstName("Rumbi").lastName("Moyo")
                .email("organizer@example.com").phoneNumber("+263770000001")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.EVENT_ORGANIZER))
                .defaultServices(new LinkedHashSet<>(List.of("ticketing")))
                .active(true)
                .build();
        userRepository.save(organizer);

        // Holds both business roles — must appear exactly once.
        User both = User.builder()
                .firstName("Kuda").lastName("Dube")
                .email("both@example.com").phoneNumber("+263770000009")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.EVENT_ORGANIZER, User.Role.MERCHANT_ADMIN))
                .defaultServices(new LinkedHashSet<>(List.of("ticketing", "loyalty")))
                .active(true)
                .build();
        userRepository.save(both);

        // Shop-level staff must NOT appear.
        User shopAdmin = User.builder()
                .firstName("Shop").lastName("Admin")
                .email("shop-admin@example.com").phoneNumber("+263770000004")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .active(true)
                .build();
        userRepository.save(shopAdmin);

        String body = mockMvc.perform(get("/admin/users/merchants")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Both business roles are included now.
        assertThat(body).contains("tendai@acme-merch.co.zw");
        assertThat(body).contains("organizer@example.com");
        assertThat(body).contains("both@example.com");
        // The dual-role user appears exactly once (no duplicate from the JOIN).
        assertThat(body.split("both@example.com", -1).length - 1).isEqualTo(1);
        // Shop-level staff are still excluded.
        assertThat(body).doesNotContain("shop-admin@example.com");
        // Business details for the business account are surfaced.
        assertThat(body).contains("businessDetails");
        assertThat(body).contains("Acme Merchandising (Pvt) Ltd");
        assertThat(body).contains("CR-2026-00891");
        assertThat(body).contains("BPO-44512");
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void listMerchants_withActiveFalse_returnsOnlyInactiveAccounts() throws Exception {
        User activeMerchant = User.builder()
                .firstName("Active").lastName("Merchant")
                .email("active-merch@example.com").phoneNumber("+263770000002")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .defaultServices(new LinkedHashSet<>(List.of("loyalty")))
                .active(true)
                .build();
        userRepository.save(activeMerchant);

        User pendingMerchant = User.builder()
                .firstName("Pending").lastName("Merchant")
                .email("pending-merch@example.com").phoneNumber("+263770000003")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .defaultServices(new LinkedHashSet<>(List.of("loyalty")))
                .active(false)
                .build();
        userRepository.save(pendingMerchant);

        // Inactive event organizer must also surface under active=false.
        User pendingOrganizer = User.builder()
                .firstName("Pending").lastName("Organizer")
                .email("pending-organizer@example.com").phoneNumber("+263770000005")
                .password(passwordEncoder.encode("Password123"))
                .roles(EnumSet.of(User.Role.EVENT_ORGANIZER))
                .defaultServices(new LinkedHashSet<>(List.of("ticketing")))
                .active(false)
                .build();
        userRepository.save(pendingOrganizer);

        String body = mockMvc.perform(get("/admin/users/merchants?active=false")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("pending-merch@example.com");
        assertThat(body).contains("pending-organizer@example.com");
        assertThat(body).doesNotContain("active-merch@example.com");
    }

    @Test
    @WithMockUser(roles = "EVENT_ORGANIZER")
    void listMerchants_asEventOrganizer_isForbidden() throws Exception {
        mockMvc.perform(get("/admin/users/merchants")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void listMerchants_unauthenticated_isDenied() throws Exception {
        mockMvc.perform(get("/admin/users/merchants")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());
    }
}
