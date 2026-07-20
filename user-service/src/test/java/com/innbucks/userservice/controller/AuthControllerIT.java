package com.innbucks.userservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.client.OradianClient;
import com.innbucks.userservice.client.OradianCustomerResponse;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OtpRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.UserAdminService;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    @Autowired UserAdminService userAdminService;
    @Autowired OtpRepository otpRepository;
    @Autowired PasswordEncoder passwordEncoder;

    // Tier-2 registration mirrors the customer into Oradian via this client;
    // hitting a real Oradian instance from a test is neither possible nor
    // desirable, so the test stubs it to return a synthetic success.
    // Without this stub the @Transactional save rolls back and the endpoint
    // 502s — which is what was masking this test as "passing" until Failsafe
    // started actually running it.
    @MockitoBean OradianClient oradianClient;

    // OTP delivery now goes through the external WhatsApp gateway; stub it so
    // the customer-register flow makes no real network call. The OTP is still
    // generated + persisted, so the tier-2 test reads the real code back from
    // OtpRepository to verify.
    @MockitoBean WhatsAppNotificationClient whatsAppNotificationClient;

    @BeforeEach
    void stubOradianSuccess() {
        OradianCustomerResponse fake = new OradianCustomerResponse();
        fake.setCustomerId(java.util.UUID.randomUUID().toString());
        fake.setOradianClientId(1001L);
        fake.setOradianExternalId("stub-oradian-external");
        when(oradianClient.createCustomer(any(), any())).thenReturn(fake);
    }

    // Approval == first activation: assigns a RANDOM one-time temp password and
    // flags must-change, exactly as PUT /admin/users/{id}/active does. A
    // registered account has no usable password until this runs.
    //
    // Because the assigned password is now random + delivered via notification
    // (not the old shared #Pass123), the test can't know it — so approve()
    // deterministically overwrites it with this known value afterwards, giving
    // the login assertions a credential to use.
    private static final String KNOWN_LOGIN_PASSWORD = "Test-Login-9xyz";

    private void approve(String email) {
        User u = userRepository.findByEmail(email).orElseThrow();
        userAdminService.setActive(u.getId(), true);          // real flow: random password assigned
        User approved = userRepository.findByEmail(email).orElseThrow();
        approved.setPassword(passwordEncoder.encode(KNOWN_LOGIN_PASSWORD));  // override for deterministic login
        userRepository.save(approved);
    }

    /**
     * Runs an approved system user through the full WEB MFA enrolment flow and
     * returns the final enroll/complete response body (which carries the real
     * access + refresh tokens and the one-time backup codes). Used by tests that
     * need a genuinely-MFA'd session rather than the USSD bypass.
     */
    private String enrolSystemUserOnWeb(String email) throws Exception {
        LoginPayload login = new LoginPayload();
        login.identifier = email;
        login.password = KNOWN_LOGIN_PASSWORD;
        String loginBody = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)   // WEB by default
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfaEnrollmentRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        String mfaToken = objectMapper.readTree(loginBody).at("/data/mfaToken").asText();

        String startBody = mockMvc.perform(post("/auth/mfa/enroll/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaToken", mfaToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secret = objectMapper.readTree(startBody).at("/data/secret").asText();
        String echoedToken = objectMapper.readTree(startBody).at("/data/mfaToken").asText();

        Map<String, String> complete = Map.of("mfaToken", echoedToken, "code", totpCode(secret));
        return mockMvc.perform(post("/auth/mfa/enroll/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(complete)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Generates the current TOTP for a base32 secret using the same defaults
     *  (SHA-1 / 6 digits / 30s) the server's verifier uses. */
    private static String totpCode(String secret) {
        try {
            long step = new dev.samstevens.totp.time.SystemTimeProvider().getTime() / 30;
            return new dev.samstevens.totp.code.DefaultCodeGenerator().generate(secret, step);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TOTP for test", e);
        }
    }

    private RegisterPayload baseSystemPayload(String email, String phone, String bundle) {
        RegisterPayload payload = new RegisterPayload();
        payload.firstName = "Tawanda";
        payload.middleName = "M";
        payload.lastName = "Mpofu";
        payload.phoneNumber = phone;
        payload.email = email;
        payload.country = "Zimbabwe";
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
    void login_systemUser_ussdHeaderFromPublicEdge_cannotBypassMfa() throws Exception {
        // Security regression guard (A07): USSD/WhatsApp are MFA-exempt channels,
        // but the channel arrives on the PUBLIC /auth/login as the untrusted
        // X-Auth-Channel header. A password-holding attacker must NOT be able to
        // send `X-Auth-Channel: USSD` to skip the second factor on a privileged
        // account — the public edge clamps the channel to WEB (see
        // AuthChannel.forPublicLogin), so a system user still gets the enrolment
        // challenge (mfaEnrollmentRequired + mfaToken), NOT a JWT. A genuine
        // USSD/WhatsApp login would originate from a trusted server-side adapter,
        // not this header.
        RegisterPayload register = baseSystemPayload("user1@example.com", "0777000001", "loyalty");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());
        approve("user1@example.com");

        LoginPayload login = new LoginPayload();
        login.identifier = "user1@example.com";
        login.password = KNOWN_LOGIN_PASSWORD;

        mockMvc.perform(post("/auth/login")
                        .header("X-Auth-Channel", "USSD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.mfaEnrollmentRequired").value(true))
                .andExpect(jsonPath("$.data.mfaToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void login_systemUser_onWeb_requiresMfaEnrollment() throws Exception {
        // The new policy: a system user (any non-CUSTOMER role) on WEB/MOBILE
        // that hasn't enrolled gets an enrolment challenge, NOT a JWT. The step-1
        // response carries mfaEnrollmentRequired + a short-lived mfaToken and
        // deliberately omits the access token (AuthResponseDTO is NON_NULL).
        RegisterPayload register = baseSystemPayload("mfaweb@example.com", "0777000021", "loyalty");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());
        approve("mfaweb@example.com");

        LoginPayload login = new LoginPayload();
        login.identifier = "mfaweb@example.com";
        login.password = KNOWN_LOGIN_PASSWORD;

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)   // no channel header -> defaults to WEB
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfaEnrollmentRequired").value(true))
                .andExpect(jsonPath("$.data.mfaToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void login_systemUser_onWeb_completesMfaEnrollment_returnsTokens() throws Exception {
        // Full WEB two-step flow with a real, library-generated TOTP: login ->
        // enrolment challenge -> enroll/start (secret + QR) -> enroll/complete
        // with a live code -> real tokens + 10 one-time backup codes. This is the
        // end-to-end test that exercises the enroll->issueToken DB path.
        RegisterPayload register = baseSystemPayload("mfaenrol@example.com", "0777000023", "loyalty");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());
        approve("mfaenrol@example.com");

        String tokensBody = enrolSystemUserOnWeb("mfaenrol@example.com");
        var data = objectMapper.readTree(tokensBody).at("/data");
        assertThat(data.at("/token").asText()).isNotBlank();
        assertThat(data.at("/refreshToken").asText()).isNotBlank();
        assertThat(data.at("/backupCodes").size()).isEqualTo(10);
    }

    @Test
    void login_withWrongPassword_returns400() throws Exception {
        RegisterPayload register = baseSystemPayload("user2@example.com", "0777000002", "loyalty");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());
        // Approve first: a freshly-registered account is pending approval and now
        // returns 403 (account_pending_approval), so it must be activated before
        // this test can exercise the wrong-password -> 400 path.
        approve("user2@example.com");

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
    void login_pendingApproval_returns403_notInvalidCredentials() throws Exception {
        // A system user who has registered but not yet been approved by a
        // SUPER_ADMIN must be told they're pending — NOT given the generic
        // "Invalid credentials", which is what the placeholder password would
        // otherwise produce.
        RegisterPayload register = baseSystemPayload("pendingapproval@example.com", "0777000042", "loyalty");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginPayload login = new LoginPayload();
        login.identifier = "pendingapproval@example.com";
        login.password = "whatever-they-type";

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("account_pending_approval"))
                .andExpect(jsonPath("$.message", containsString("pending approval")));
    }

    @Test
    void refresh_rotatesAndDetectsReuse() throws Exception {
        RegisterPayload register = baseSystemPayload("user3@example.com", "0777000003", "loyalty");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());
        approve("user3@example.com");

        // This test is about refresh-token rotation + reuse detection, not MFA,
        // so grab the initial token pair by completing the system user's WEB MFA
        // enrolment (USSD can no longer bypass MFA from the public edge).
        String loginBody = enrolSystemUserOnWeb("user3@example.com");
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
        // Customer types the national form; the service canonicalises to E.164
        // before storing, and the response echoes the stored (canonical) value.
        req.put("phoneNumber", "0777000010");
        req.put("password", "password123");

        mockMvc.perform(post("/auth/customer/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tier").value(1))
                .andExpect(jsonPath("$.data.phoneNumber").value("+263777000010"))
                .andExpect(jsonPath("$.data.verified").value(false));
    }

    @Test
    void customerTier2_upgradesUsingMsisdnAndStructuredAddress() throws Exception {
        // Canonical E.164 throughout: phone is stored, OTP-keyed, and echoed in
        // this one form, so the OTP read-back and the response assertion align.
        String phone = "+263712345678";

        // Tier 1: stash pending registration + dispatch OTP.
        Map<String, Object> tier1 = new HashMap<>();
        tier1.put("phoneNumber", phone);
        tier1.put("password", "password123");
        mockMvc.perform(post("/auth/customer/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tier1)))
                .andExpect(status().isCreated());

        // OTP is a random 6-digit code delivered via the mocked WhatsApp gateway.
        // A02: otps.code now stores the HMAC of the code, not the raw digits, so
        // recover the delivered code from the WhatsApp mock (not the DB) and
        // verify with it to materialise the User + Tier-1 profile.
        org.mockito.ArgumentCaptor<String> otpMsg = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(whatsAppNotificationClient)
                .sendCustomNotification(org.mockito.ArgumentMatchers.eq(phone), otpMsg.capture());
        java.util.regex.Matcher otpMatcher =
                java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(otpMsg.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(otpMatcher.find(),
                "OTP message should carry a 6-digit code");
        String code = otpMatcher.group(1);
        Map<String, String> verify = Map.of("phoneNumber", phone, "code", code);
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
        public String country;
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
