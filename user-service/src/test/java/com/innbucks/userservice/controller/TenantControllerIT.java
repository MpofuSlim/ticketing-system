package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserRepository userRepository;
    @Autowired TenantProfileRepository tenantProfileRepository;
    @Autowired CustomerProfileRepository customerProfileRepository;
    @Autowired DeviceRepository deviceRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDb() {
        tenantProfileRepository.deleteAll();
        customerProfileRepository.deleteAll();
        deviceRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getProfile_requiresAuth() throws Exception {
        mockMvc.perform(get("/tenant/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getProfile_withValidJwt_returnsTenantProfileDto() throws Exception {
        User user = userRepository.save(User.builder()
                .firstName("A")
                .lastName("B")
                .phoneNumber("0777000003")
                .email("tenant2@example.com")
                .password(passwordEncoder.encode("password123"))
                .role(User.Role.TENANT)
                .build());

        tenantProfileRepository.save(TenantProfile.builder()
                .user(user)
                .businessName("My Biz")
                .totalEvents(7)
                .rating(4.5)
                .build());

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name());

        mockMvc.perform(get("/tenant/profile")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.businessName").value("My Biz"))
                .andExpect(jsonPath("$.data.totalEvents").value(7))
                .andExpect(jsonPath("$.data.rating").value(4.5));
    }
}
