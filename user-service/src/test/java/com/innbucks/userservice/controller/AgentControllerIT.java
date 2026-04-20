package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.AgentProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.AgentProfileRepository;
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
class AgentControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserRepository userRepository;
    @Autowired AgentProfileRepository agentProfileRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDb() {
        agentProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getProfile_requiresAuth() throws Exception {
        mockMvc.perform(get("/agent/profile"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getProfile_withValidJwt_returnsAgentProfileDto() throws Exception {
        User user = userRepository.save(User.builder()
                .firstName("A")
                .lastName("B")
                .phoneNumber("0777000003")
                .email("agent2@example.com")
                .password(passwordEncoder.encode("password123"))
                .role(User.Role.AGENT)
                .build());

        agentProfileRepository.save(AgentProfile.builder()
                .user(user)
                .businessName("My Biz")
                .totalEvents(7)
                .rating(4.5)
                .build());

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name());

        mockMvc.perform(get("/agent/profile")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.businessName").value("My Biz"))
                .andExpect(jsonPath("$.totalEvents").value(7))
                .andExpect(jsonPath("$.rating").value(4.5));
    }
}

