package com.innbucks.userservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;

    private void activate(String email) {
        User u = userRepository.findByEmail(email).orElseThrow();
        u.setActive(true);
        userRepository.save(u);
    }

    private RegisterPayload baseSystemPayload(String email, String phone, String bundle) {
        RegisterPayload payload = new RegisterPayload();
        payload.firstName = "Tawanda";
        payload.middleName = "M";
        payload.lastName = "Mpofu";
        payload.phoneNumber = phone;
        payload.email = email;
        payload.password = "password123";
        payload.defaultServices = List.of(bundle);
        return payload;
    }

    @Test
    void register_systemUser_createsUser_andDoesNotReturnJwt() throws Exception {
        RegisterPayload payload = baseSystemPayload("tenant1@example.com", "0777000000", "ticketing");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("201 CREATED"))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.email").value("tenant1@example.com"))
                .andExpect(jsonPath("$.data.roles[0]").value("EVENT_ORGANIZER"))
                .andExpect(jsonPath("$.data.mfaRequired").value(false));
    }

    @Test
    void login_withValidCredentials_returnsJwt() throws Exception {
        RegisterPayload register = baseSystemPayload("user1@example.com", "0777000001", "loyalty");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());
        activate("user1@example.com");

        LoginPayload login = new LoginPayload();
        login.identifier = "user1@example.com";
        login.password = "password123";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("200 OK"))
                .andExpect(jsonPath("$.data.token", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.refreshToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.email").value("user1@example.com"))
                .andExpect(jsonPath("$.data.roles[0]").value("MERCHANT_ADMIN"))
                .andExpect(jsonPath("$.data.mfaRequired").value(false));
    }

    @Test
    void login_withWrongPassword_returns400() throws Exception {
        RegisterPayload register = baseSystemPayload("user2@example.com", "0777000002", "loyalty");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginPayload login = new LoginPayload();
        login.identifier = "user2@example.com";
        login.password = "wrong-password";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Invalid credentials")));
    }

    @Test
    void customerTier1_registersByPhoneAndPassword() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("phoneNumber", "0777000010");
        req.put("password", "password123");

        mockMvc.perform(post("/auth/customer/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tier").value(1))
                .andExpect(jsonPath("$.data.phoneNumber").value("0777000010"))
                .andExpect(jsonPath("$.data.verified").value(false));
    }

    static class RegisterPayload {
        public String firstName;
        public String middleName;
        public String lastName;
        public String phoneNumber;
        public String email;
        public String password;
        public List<String> defaultServices;
    }

    static class LoginPayload {
        public String identifier;
        public String password;
    }
}
