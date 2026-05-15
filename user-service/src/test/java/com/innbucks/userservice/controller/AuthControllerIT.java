package com.innbucks.userservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.client.OradianClient;
import com.innbucks.userservice.client.OradianCustomerResponse;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;

    // Tier-2 registration mirrors the customer into Oradian via this client;
    // hitting a real Oradian instance from a test is neither possible nor
    // desirable, so the test stubs it to return a synthetic success.
    // Without this stub the @Transactional save rolls back and the endpoint
    // 502s — which is what was masking this test as "passing" until Failsafe
    // started actually running it.
    @MockitoBean OradianClient oradianClient;

    @BeforeEach
    void stubOradianSuccess() {
        OradianCustomerResponse fake = new OradianCustomerResponse();
        fake.setCustomerId(java.util.UUID.randomUUID().toString());
        fake.setOradianClientId(1001L);
        fake.setOradianExternalId("stub-oradian-external");
        when(oradianClient.createCustomer(any())).thenReturn(fake);
    }

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
    void refresh_rotatesAndDetectsReuse() throws Exception {
        RegisterPayload register = baseSystemPayload("user3@example.com", "0777000003", "loyalty");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());
        activate("user3@example.com");

        LoginPayload login = new LoginPayload();
        login.identifier = "user3@example.com";
        login.password = "password123";
        String loginBody = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String originalRefresh = objectMapper.readTree(loginBody).at("/data/refreshToken").asText();
        assertThat(originalRefresh).isNotBlank();

        // First rotation succeeds and yields a brand-new refresh token.
        String rotatedBody = mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + originalRefresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.token", not(blankOrNullString())))
                .andReturn().getResponse().getContentAsString();
        String newRefresh = objectMapper.readTree(rotatedBody).at("/data/refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(originalRefresh);

        // Replaying the rotated-out token must fail and revoke the family.
        mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + originalRefresh))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("reuse detected")));

        // The (previously valid) successor is also now revoked — family kill.
        mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + newRefresh))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("reuse detected")));
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

    @Test
    void customerTier2_upgradesUsingMsisdnAndStructuredAddress() throws Exception {
        String phone = "0712345678";

        // Tier 1: stash pending registration + dispatch OTP.
        Map<String, Object> tier1 = new HashMap<>();
        tier1.put("phoneNumber", phone);
        tier1.put("password", "password123");
        mockMvc.perform(post("/auth/customer/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tier1)))
                .andExpect(status().isCreated());

        // Verify OTP (dev fixed code = 000000) — materialises the User + Tier-1 profile.
        Map<String, String> verify = Map.of("phoneNumber", phone, "code", "000000");
        mockMvc.perform(post("/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verify)))
                .andExpect(status().isOk());

        // Tier 2: send the exact shape the frontend asked for.
        String tier2Body = """
                {
                  "firstName": "Sarah",
                  "middleName": "Tiffany",
                  "lastName": "Moyo",
                  "dateOfBirth": "2001-01-01",
                  "gender": "FEMALE",
                  "msisdn": "%s",
                  "nationalId": "5337888V72",
                  "email": "sarah@example.com",
                  "address": {
                    "street1": "P.O. Box 12345",
                    "city": "Nairobi",
                    "postCode": "00100",
                    "country": "Kenya"
                  },
                  "clientCustomFields": {
                    "referralCode": "FRIEND-42",
                    "campaign": "spring-2026"
                  }
                }
                """.formatted(phone);

        mockMvc.perform(post("/auth/customer/register/tier2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier2Body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200 OK"))
                .andExpect(jsonPath("$.data.tier").value(2))
                .andExpect(jsonPath("$.data.phoneNumber").value(phone))
                .andExpect(jsonPath("$.data.verified").value(false));
    }

    @Test
    void customerTier2_returns400WhenMsisdnDoesNotMatchAnyTier1() throws Exception {
        String tier2Body = """
                {
                  "firstName": "Sarah",
                  "lastName": "Moyo",
                  "dateOfBirth": "2001-01-01",
                  "gender": "FEMALE",
                  "msisdn": "0700000000",
                  "nationalId": "X",
                  "email": "x@example.com",
                  "address": {
                    "street1": "a", "city": "b", "postCode": "c", "country": "d"
                  }
                }
                """;
        mockMvc.perform(post("/auth/customer/register/tier2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier2Body))
                .andExpect(status().isBadRequest());
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

    @Test
    void tier2Registration_missingFields_returnsFieldLevelMessages() throws Exception {
        // Empty body — the DTO has @NotBlank/@NotNull on every required
        // field, so the request should fail validation and the handler
        // should surface each field's friendly message. Previously the
        // request fell through to Spring's DefaultHandlerExceptionResolver
        // which returned a single cryptic "Validation failed for object='…'".
        // email is intentionally NOT in the asserted set — it's optional
        // on tier-2 (many customers have no email), so an empty body must
        // NOT produce a per-field error for it.
        mockMvc.perform(post("/auth/customer/register/tier2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400 BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.firstName").value("First name is required"))
                .andExpect(jsonPath("$.data.lastName").value("Last name is required"))
                .andExpect(jsonPath("$.data.msisdn").value("msisdn is required"))
                .andExpect(jsonPath("$.data.email").doesNotExist());
    }

    @Test
    void tier2Registration_invalidEmail_stillRejects() throws Exception {
        // Email is optional, but if supplied it must be a valid address —
        // @Email kicks in. This guards the "@NotBlank gone, but @Email kept"
        // shape so a future change can't accidentally accept "not-an-email"
        // through this endpoint.
        mockMvc.perform(post("/auth/customer/register/tier2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.email").value("Email must be a valid email address"));
    }
}
