package com.innbucks.userservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
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

    private RegisterPayload baseSystemPayload(String email, String phone, String role) {
        RegisterPayload payload = new RegisterPayload();
        payload.firstName = "Tawanda";
        payload.middleName = "M";
        payload.lastName = "Mpofu";
        payload.phoneNumber = phone;
        payload.email = email;
        payload.password = "password123";
        payload.role = role;

        Map<String, Object> device = new HashMap<>();
        device.put("deviceId", "device-" + phone);
        device.put("deviceName", "iPhone");
        device.put("platform", "iOS");
        device.put("pushToken", "tok");
        payload.device = device;

        Map<String, Object> mfa = new HashMap<>();
        mfa.put("method", "TOTP");
        mfa.put("secret", "SECRET");
        payload.mfa = mfa;

        return payload;
    }

    @Test
    void register_systemUser_createsUser_andDoesNotReturnJwt() throws Exception {
        RegisterPayload payload = baseSystemPayload("tenant1@example.com", "0777000000", "EVENT_ORGANIZER");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("201 CREATED"))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.email").value("tenant1@example.com"))
                .andExpect(jsonPath("$.data.role").value("EVENT_ORGANIZER"))
                .andExpect(jsonPath("$.data.mfaRequired").value(false));
    }

    @Test
    void login_withValidCredentials_returnsJwt() throws Exception {
        RegisterPayload register = baseSystemPayload("user1@example.com", "0777000001", "MERCHANT_ADMIN");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        // The registered user has MFA enabled by default; supply OTP to pass the gate.
        LoginPayload login = new LoginPayload();
        login.email = "user1@example.com";
        login.password = "password123";
        login.otpCode = "123456";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("200 OK"))
                .andExpect(jsonPath("$.data.token", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.email").value("user1@example.com"))
                .andExpect(jsonPath("$.data.role").value("MERCHANT_ADMIN"))
                .andExpect(jsonPath("$.data.mfaRequired").value(false));
    }

    @Test
    void login_withWrongPassword_returns400() throws Exception {
        RegisterPayload register = baseSystemPayload("user2@example.com", "0777000002", "MERCHANT_ADMIN");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginPayload login = new LoginPayload();
        login.email = "user2@example.com";
        login.password = "wrong-password";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400 BAD_REQUEST"))
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
        public String role;
        public Map<String, Object> device;
        public Map<String, Object> mfa;
    }

    static class LoginPayload {
        public String email;
        public String phoneNumber;
        public String password;
        public String otpCode;
    }
}
