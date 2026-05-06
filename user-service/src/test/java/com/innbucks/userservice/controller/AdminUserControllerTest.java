package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.User;
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
}
