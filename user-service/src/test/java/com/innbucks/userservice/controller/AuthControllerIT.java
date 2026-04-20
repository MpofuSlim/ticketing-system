package com.innbucks.userservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void register_createsUser_andDoesNotReturnJwt() throws Exception {
        var payload = new RegisterPayload();
        payload.firstName = "Tawanda";
        payload.lastName = "Mpofu";
        payload.phoneNumber = "0777000000";
        payload.email = "agent1@example.com";
        payload.password = "password123";
        payload.role = "AGENT";
        payload.businessName = "Acme Events";
        payload.businessAddress = "1 Main Street";
        payload.businessEmail = "biz@example.com";
        payload.businessPhoneNumber = "0777111222";
        payload.registrationNumber = "REG-123";
        payload.metaData = "path/to/file.pdf";

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.email").value("agent1@example.com"))
                .andExpect(jsonPath("$.role").value("AGENT"))
                .andExpect(jsonPath("$.mfaRequired").value(false));
    }

    @Test
    void login_withValidCredentials_returnsJwt() throws Exception {
        // register first
        var register = new RegisterPayload();
        register.firstName = "Jane";
        register.lastName = "Doe";
        register.phoneNumber = "0777000001";
        register.email = "user1@example.com";
        register.password = "password123";
        register.role = "CUSTOMER";

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        var login = new LoginPayload();
        login.email = "user1@example.com";
        login.password = "password123";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token", not(blankOrNullString())))
                .andExpect(jsonPath("$.email").value("user1@example.com"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.mfaRequired").value(false));
    }

    @Test
    void login_withWrongPassword_returns400() throws Exception {
        // register first
        var register = new RegisterPayload();
        register.firstName = "Joe";
        register.lastName = "Bloggs";
        register.phoneNumber = "0777000002";
        register.email = "user2@example.com";
        register.password = "password123";
        register.role = "CUSTOMER";

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        var login = new LoginPayload();
        login.email = "user2@example.com";
        login.password = "wrong-password";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Invalid credentials")));
    }

    // Minimal JSON payloads for tests (avoid coupling to DTO classes)
    static class RegisterPayload {
        public String firstName;
        public String lastName;
        public String phoneNumber;
        public String email;
        public String password;
        public String role;
        public String businessName;
        public String businessAddress;
        public String businessEmail;
        public String businessPhoneNumber;
        public String registrationNumber;
        public String metaData;
    }

    static class LoginPayload {
        public String email;
        public String password;
        public String otpCode;
    }
}

