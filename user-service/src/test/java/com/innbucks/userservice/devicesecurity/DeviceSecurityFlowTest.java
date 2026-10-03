package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DTX device security end to end, through the real HTTP stack, security chain
 * and schema: the contract's sign-in flow (§5, §13), the *569# unlock and block
 * menus (§9), Your devices and the in-session step-up (§5.5, §5.6), and the
 * call-center console. Staging and the two message gateways are the only fakes.
 *
 * <p>Refusals are asserted with their EXACT status and errorCode — never
 * {@code is4xxClientError()} — so a Spring Security 401 can never pass for the
 * controller's own partner-key 401 (the fleet's three-files-must-agree lesson).
 */
@SpringBootTest
@ActiveProfiles("test")
class DeviceSecurityFlowTest {

    static final String BROKER_KEY = "test-broker-key-0123456789abcdef0123456789";
    static final String USSD_KEY = "test-ussd-key-0123456789abcdef0123456789abc";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("device-security.enabled", () -> "true");
        r.add("device-security.production", () -> "false");
        r.add("device-security.enforce.otp", () -> "true");
        r.add("device-security.enforce.blocks", () -> "true");
        r.add("device-security.enforce.bans", () -> "true");
        r.add("device-security.broker-api-key", () -> BROKER_KEY);
        r.add("device-security.ussd-api-key", () -> USSD_KEY);
        r.add("device-security.ticket.private-key", TestKeys::privatePem);
        r.add("device-security.support-phone", () -> "0867 700 0000");
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceSecurityProperties properties;
    @Autowired UserRepository users;
    @Autowired LoginTicketSigner signer;
    @Autowired com.innbucks.userservice.security.JwtUtil jwtUtil;

    @MockitoBean StagingClientServiceClient staging;
    @MockitoBean WhatsAppNotificationClient whatsApp;
    @MockitoBean SmsNotificationClient sms;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.execute("TRUNCATE customer_devices, customer_security_profiles, device_otp_challenges, "
                + "device_login_tickets, device_security_events, device_wide_bans, device_sign_in_locations CASCADE");
        properties.setEnabled(true);
        properties.getEnforce().setOtp(true);
        properties.getEnforce().setBlocks(true);
        properties.getEnforce().setBans(true);
        when(staging.isConfigured()).thenReturn(true);
        when(staging.token()).thenReturn(new StagingClientServiceClient.ClientServiceToken("staging-client-token",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)));
    }

    // =====================================================================================
    // The sign-in flow (§13)
    // =====================================================================================

    @Test
    @DisplayName("new phone: OTP → PIN → trusted; the next sign-in is PIN-only, with the new-phone notice")
    void newPhone_otpThenPin_becomesTrusted() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();

        String first = clientService(msisdn, install, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(first, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        assertThat((String) JsonPath.read(first, "$.message")).isEqualTo("Let's confirm it's you on this phone.");
        assertThat((List<String>) JsonPath.read(first, "$.data.channels")).containsExactly("WHATSAPP", "SMS");
        assertThat((String) JsonPath.read(first, "$.data.reason")).isEqualTo("NEW_DEVICE");
        String challenge = JsonPath.read(first, "$.data.challengeId");

        // A retry of the same sign-in reuses the live challenge — nothing new is counted.
        assertThat((String) JsonPath.read(clientService(msisdn, install, "SIGN_IN", "{}"), "$.data.challengeId"))
                .isEqualTo(challenge);

        String code = sendAndCapture(challenge, "WHATSAPP", install, msisdn);

        // A wrong code spends an attempt and says how many are left.
        mvc.perform(broker(post("/auth/client-service/otp/verify"), install)
                        .content("{\"challengeId\":\"" + challenge + "\",\"otp\":\"" + wrong(code) + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("otp_incorrect"))
                .andExpect(jsonPath("$.data.attemptsLeft").value(2));

        // A code typed on another phone is refused (OTP relay, §8.2).
        mvc.perform(broker(post("/auth/client-service/otp/verify"), UUID.randomUUID().toString())
                        .content("{\"challengeId\":\"" + challenge + "\",\"otp\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());

        String token = verifyOtp(challenge, code, install);
        assertThat((String) JsonPath.read(token, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(token, "$.data.clientService.accessToken")).isEqualTo("staging-client-token");
        assertThat((String) JsonPath.read(token, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        assertThat((Boolean) JsonPath.read(token, "$.data.trust.newDevice")).isTrue();
        String ticket = JsonPath.read(token, "$.data.loginTicket");

        // The broker spends the ticket once, bound to the number and the device.
        redeem(ticket, msisdn, install, "SIGN_IN").andExpect(jsonPath("$.data.valid").value(true));
        redeem(ticket, msisdn, install, "SIGN_IN").andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.reason").value("ALREADY_USED"));

        loginResult(jti(ticket), "SUCCESS")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));
        loginResult(jti(ticket), "SUCCESS").andExpect(jsonPath("$.data.recorded").value(false));

        // The customer is told a new phone signed in, on the channel they chose.
        verify(whatsApp, timeout(5000)).sendCustomNotification(eq(msisdn), contains("A new phone"));

        // Trusted now: the next sign-in (and every silent renewal) needs no code.
        String second = clientService(msisdn, install, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(second, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(second, "$.data.trust.state")).isEqualTo("TRUSTED");
        // The new-phone period is running on the row, but no service lowers limits
        // for it yet, so the app is told nothing about reduced limits.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_devices WHERE msisdn = ? AND cooling_until IS NOT NULL",
                Integer.class, msisdn)).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(second, "$.data.limits.cooling")).isFalse();
        assertThat((Object) JsonPath.read(second, "$.data.limits.coolingUntil")).isNull();
        assertThat((String) JsonPath.read(second, "$.message")).isEqualTo("Enter your PIN to continue.");
        assertThat((String) JsonPath.read(clientService(msisdn, install, "RENEW", "{}"), "$.data.decision"))
                .isEqualTo("TOKEN");
    }

    @Test
    @DisplayName("a trusted phone is not asked for a code on every login when the broker reports app check 'absent' (2026-09-30 loop)")
    void trustedPhone_appCheckAbsent_signsInWithoutACode() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        trustedPhone(msisdn, install);

        // What the broker sends on every request today.
        for (int login = 0; login < 3; login++) {
            String res = mvc.perform(broker(post("/auth/client-service"), install)
                            .header("x-innbucks-app-check", "absent")
                            .content(signInBody(msisdn, install, "SIGN_IN", "{}")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat((String) JsonPath.read(res, "$.data.decision")).isEqualTo("TOKEN");
        }
        // Recorded for the fraud desk, just not asked about again.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_security_events WHERE msisdn = ? "
                + "AND event_type = 'SIGN_IN_DECISION' AND features LIKE '%APP_CHECK_ABSENT%'", Integer.class, msisdn))
                .isEqualTo(3);

        // A phone that has never proved itself is still asked.
        String freshInstall = UUID.randomUUID().toString();
        String fresh = mvc.perform(broker(post("/auth/client-service"), freshInstall)
                        .header("x-innbucks-app-check", "absent")
                        .content(signInBody(number(), freshInstall, "SIGN_IN", "{}")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(fresh).contains("OTP_REQUIRED").doesNotContain("\"TOKEN\"");
    }

    @Test
    @DisplayName("a ticket for one number or one phone cannot authorise another")
    void ticket_isBoundToNumberAndDevice() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        String ticket = JsonPath.read(trustedPhone(msisdn, install), "$.data.loginTicket");

        redeem(ticket, number(), install, "SIGN_IN").andExpect(jsonPath("$.data.reason").value("MSISDN_MISMATCH"));
        redeem(ticket, msisdn, UUID.randomUUID().toString(), "SIGN_IN")
                .andExpect(jsonPath("$.data.reason").value("DEVICE_MISMATCH"));
        redeem(ticket, msisdn, install, "PIN_ISSUE").andExpect(jsonPath("$.data.reason").value("PURPOSE_MISMATCH"));
        redeem("not.a.ticket", msisdn, install, "SIGN_IN").andExpect(jsonPath("$.data.reason").value("INVALID"));
    }

    @Test
    @DisplayName("the JWKS the broker verifies tickets with is public")
    void jwks_isPublished() throws Exception {
        mvc.perform(get("/device-security/broker/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value("dtx-ticket-1"));
    }

    @Test
    @DisplayName("WhatsApp down: 503 names the channel, SMS is offered and works; an early resend is a 429 with the timer")
    void providerDown_offersTheOtherChannel() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        String challenge = JsonPath.read(clientService(msisdn, install, "SIGN_IN", "{}"), "$.data.challengeId");
        org.mockito.Mockito.doThrow(new com.innbucks.userservice.client.NotificationDeliveryException("down"))
                .when(whatsApp).sendCustomNotification(eq(msisdn), anyString());

        mvc.perform(broker(post("/auth/client-service/otp/send"), install)
                        .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"WHATSAPP\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("channel_unavailable"))
                .andExpect(jsonPath("$.data.channel").value("WHATSAPP"))
                .andExpect(jsonPath("$.data.alternatives[0]").value("SMS"));

        // The same sign-in now offers SMS only, and the failed send cost no resend.
        String again = clientService(msisdn, install, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(again, "$.data.challengeId")).isEqualTo(challenge);
        assertThat((List<String>) JsonPath.read(again, "$.data.channels")).containsExactly("SMS");
        String sent = mvc.perform(broker(post("/auth/client-service/otp/send"), install)
                        .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"SMS\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((Integer) JsonPath.read(sent, "$.data.resendsLeft")).isEqualTo(2);

        mvc.perform(broker(post("/auth/client-service/otp/send"), install)
                        .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"SMS\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"))
                .andExpect(jsonPath("$.data.errorCode").value("resend_too_soon"))
                .andExpect(jsonPath("$.data.challengeId").value(challenge))
                .andExpect(jsonPath("$.data.resendAfter").exists());
    }

    @Test
    @DisplayName("staging test numbers get the fixed code and no message (§11, §14.1)")
    void testNumbers_useTheFixedCode() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        properties.getTestOtp().setEnabled(true);
        properties.getTestOtp().setCode("123456");
        properties.getTestOtp().setNumbers(List.of(msisdn));
        try {
            String challenge = JsonPath.read(clientService(msisdn, install, "SIGN_IN", "{}"), "$.data.challengeId");
            mvc.perform(broker(post("/auth/client-service/otp/send"), install)
                    .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"SMS\"}")).andExpect(status().isOk());
            org.mockito.Mockito.verifyNoInteractions(sms);
            assertThat((String) JsonPath.read(verifyOtp(challenge, "123456", install), "$.data.decision")).isEqualTo("TOKEN");
        } finally {
            properties.getTestOtp().setEnabled(false);
            properties.getTestOtp().setNumbers(new java.util.ArrayList<>());
        }
    }

    @Test
    @DisplayName("on production the fixed code works only with TEST_OTP_PRODUCTION_ALLOWED (the app-review account)")
    void testNumbers_onProduction_needTheExplicitOptIn() throws Exception {
        String msisdn = number();
        properties.setProduction(true);
        properties.getTestOtp().setEnabled(true);
        properties.getTestOtp().setCode("111111");
        properties.getTestOtp().setNumbers(List.of(msisdn));
        try {
            // Without the opt-in the list is refused: a real code goes out, and 111111 is just a wrong code.
            String install = UUID.randomUUID().toString();
            String challenge = JsonPath.read(clientService(msisdn, install, "SIGN_IN", "{}"), "$.data.challengeId");
            mvc.perform(broker(post("/auth/client-service/otp/send"), install)
                    .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"WHATSAPP\"}")).andExpect(status().isOk());
            verify(whatsApp, timeout(5000)).sendCustomNotification(eq(msisdn), anyString());
            mvc.perform(broker(post("/auth/client-service/otp/verify"), install)
                    .content("{\"challengeId\":\"" + challenge + "\",\"otp\":\"111111\"}"))
                    .andExpect(jsonPath("$.data.errorCode").value("otp_incorrect"));

            // With it, the review number gets the fixed code and nothing is sent.
            properties.getTestOtp().setProductionAllowed(true);
            org.mockito.Mockito.clearInvocations(whatsApp, sms);
            String install2 = UUID.randomUUID().toString();
            String challenge2 = JsonPath.read(clientService(msisdn, install2, "SIGN_IN", "{}"), "$.data.challengeId");
            mvc.perform(broker(post("/auth/client-service/otp/send"), install2)
                    .content("{\"challengeId\":\"" + challenge2 + "\",\"channel\":\"WHATSAPP\"}")).andExpect(status().isOk());
            org.mockito.Mockito.verifyNoInteractions(whatsApp, sms);
            assertThat((String) JsonPath.read(verifyOtp(challenge2, "111111", install2), "$.data.decision")).isEqualTo("TOKEN");

            // ...and only for the listed number: any other number still gets a real code.
            String other = number();
            String install3 = UUID.randomUUID().toString();
            String challenge3 = JsonPath.read(clientService(other, install3, "SIGN_IN", "{}"), "$.data.challengeId");
            mvc.perform(broker(post("/auth/client-service/otp/send"), install3)
                    .content("{\"challengeId\":\"" + challenge3 + "\",\"channel\":\"WHATSAPP\"}")).andExpect(status().isOk());
            verify(whatsApp, timeout(5000)).sendCustomNotification(eq(other), anyString());
        } finally {
            properties.setProduction(false);
            properties.getTestOtp().setEnabled(false);
            properties.getTestOtp().setProductionAllowed(false);
            properties.getTestOtp().setNumbers(new java.util.ArrayList<>());
        }
    }

    @Test
    @DisplayName("the number check (LOOKUP) never asks for a code, binds nothing, and is tightly limited")
    void lookup_isTokenWithoutBinding_andLimited() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        String body = signInBody(msisdn, install, "SIGN_IN", "{}").replace("\"purpose\":\"SIGN_IN\"", "\"purpose\":\"LOOKUP\"");
        for (int i = 0; i < 10; i++) {
            String res = mvc.perform(broker(post("/auth/client-service"), install).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat((String) JsonPath.read(res, "$.data.decision")).isEqualTo("TOKEN");
            assertThat((Object) JsonPath.read(res, "$.data.trust")).isNull();
        }
        mvc.perform(broker(post("/auth/client-service"), install).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.data.errorCode").value("rate_limited"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_devices WHERE msisdn = ?", Integer.class, msisdn))
                .isZero();
    }

    // =====================================================================================
    // Partner keys, validation order, the off switch
    // =====================================================================================

    @Test
    @DisplayName("every partner endpoint answers the CONTROLLER's 401 without its key — never Spring's")
    void partnerKeys_areRequired_andSeparate() throws Exception {
        String body = "{\"requestId\":\"r1\",\"msisdn\":\"+263771234512\",\"device\":{\"installId\":\"x\"}}";
        mvc.perform(post("/auth/client-service").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("invalid_api_key"));
        // The USSD key does not open the broker's door, nor the broker's the USSD's.
        mvc.perform(post("/auth/client-service").header("x-api-key", USSD_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/device-security/ussd/devices/blocked").header("x-api-key", BROKER_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"msisdn\":\"+263771234512\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("invalid_api_key"));
        mvc.perform(post("/device-security/broker/login-result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":\"tkt_x\",\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("invalid_api_key"));
        // An unauthenticated caller gets no validation detail: the key is checked first.
        mvc.perform(post("/auth/client-service").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a malformed request names the field (§7.7)")
    void validation_namesTheField() throws Exception {
        mvc.perform(broker(post("/auth/client-service"), null)
                        .content("{\"requestId\":\"r1\",\"msisdn\":\"+263771234512\",\"device\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("validation_failed"))
                .andExpect(jsonPath("$.data.field").value("device.installId"));
        mvc.perform(broker(post("/auth/client-service"), "header-install")
                        .content("{\"requestId\":\"r1\",\"msisdn\":\"+263771234512\",\"device\":{\"installId\":\"other\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.field").value("device.installId"));
        mvc.perform(broker(post("/auth/client-service"), null)
                        .content("{\"requestId\":\"r1\",\"msisdn\":\"12\",\"device\":{\"installId\":\"a\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.field").value("msisdn"));
    }

    @Test
    void appCheckInvalid_isRefused() throws Exception {
        mvc.perform(broker(post("/auth/client-service"), null).header("x-innbucks-app-check", "invalid")
                        .content(signInBody(number(), UUID.randomUUID().toString(), "SIGN_IN", "{}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("app_check_failed"));
    }

    @Test
    @DisplayName("switched off, every device-security endpoint is a 404 that says nothing")
    void disabled_is404() throws Exception {
        properties.setEnabled(false);
        mvc.perform(broker(post("/auth/client-service"), null).content("{}")).andExpect(status().isNotFound());
        mvc.perform(post("/device-security/ussd/devices/blocked").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/device-security/broker/jwks.json")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("watch mode: the engine's verdict is logged, the caller gets TOKEN (§14)")
    void watchMode_answersToken() throws Exception {
        properties.getEnforce().setOtp(false);
        properties.getEnforce().setBlocks(false);
        properties.getEnforce().setBans(false);
        String msisdn = number();
        String res = clientService(msisdn, UUID.randomUUID().toString(), "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(res, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(res, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        assertThat(jdbc.queryForObject("SELECT evaluated_decision FROM device_security_events "
                + "WHERE msisdn = ? AND event_type = 'SIGN_IN_DECISION'", String.class, msisdn)).isEqualTo("OTP_REQUIRED");
    }

    @Test
    @DisplayName("watch mode trust is provisional: no notice, no cooling, and one code once OTP is enforced")
    void watchModeTrust_isProvisional_untilACodeIsVerified() throws Exception {
        properties.getEnforce().setOtp(false);
        properties.getEnforce().setBlocks(false);
        properties.getEnforce().setBans(false);
        String msisdn = number();
        String install = UUID.randomUUID().toString();

        // Watching: TOKEN with no code, and the PIN login binds the phone...
        String first = clientService(msisdn, install, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(first, "$.data.decision")).isEqualTo("TOKEN");
        loginResult(jti(JsonPath.read(first, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));
        assertThat(jdbc.queryForObject("SELECT otp_verified_at IS NULL FROM customer_devices WHERE msisdn = ?",
                Boolean.class, msisdn)).isTrue();

        // ...but announces nothing: no "a new phone signed in", no new-phone cooling.
        String second = clientService(msisdn, install, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(second, "$.data.trust.state")).isEqualTo("TRUSTED");
        assertThat((Boolean) JsonPath.read(second, "$.data.limits.cooling")).isFalse();
        assertThat((String) JsonPath.read(second, "$.message")).isEqualTo("Enter your PIN to continue.");
        verify(whatsApp, after(500).never()).sendCustomNotification(eq(msisdn), anyString());
        verify(sms, never()).sendSms(eq(msisdn), anyString(), anyString());

        // Codes switched on: that trust never proved the SIM, so even a silent renewal asks once.
        properties.getEnforce().setOtp(true);
        String renew = clientService(msisdn, install, "RENEW", "{}");
        assertThat((String) JsonPath.read(renew, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        assertThat((String) JsonPath.read(renew, "$.data.reason")).isEqualTo("NEW_DEVICE");
        String challenge = JsonPath.read(renew, "$.data.challengeId");
        String token = verifyOtp(challenge, sendAndCapture(challenge, "WHATSAPP", install, msisdn), install);
        loginResult(jti(JsonPath.read(token, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));
        assertThat(jdbc.queryForObject("SELECT otp_verified_at IS NOT NULL FROM customer_devices WHERE msisdn = ?",
                Boolean.class, msisdn)).isTrue();

        // Confirmed for real: renewals are silent again, and the customer's own phone was never
        // announced as "new" — the only message they received was the code.
        assertThat((String) JsonPath.read(clientService(msisdn, install, "RENEW", "{}"), "$.data.decision"))
                .isEqualTo("TOKEN");
        verify(whatsApp, after(500).never()).sendCustomNotification(eq(msisdn), contains("A new phone"));
    }

    @Test
    @DisplayName("OTP enforced but bans not yet: a would-be ban asks for a code instead of handing out a TOKEN")
    void unenforcedBan_degradesToOtp() throws Exception {
        properties.getEnforce().setBlocks(false);
        properties.getEnforce().setBans(false);
        String res = clientService(number(), UUID.randomUUID().toString(), "SIGN_IN",
                "{\"source\":\"freerasp\",\"threats\":[\"hooks\"]}");
        assertThat((String) JsonPath.read(res, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        assertThat((String) JsonPath.read(res, "$.data.reason")).isEqualTo("RISK");
    }

    // =====================================================================================
    // Blocks, bans and *569#
    // =====================================================================================

    @Test
    @DisplayName("two dead challenges pause the phone; *569# unlocks it with an SMS; the next sign-in asks for a code")
    void deadChallenges_block_thenUssdUnlock() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        for (int round = 0; round < 2; round++) {
            String challenge = JsonPath.read(clientService(msisdn, install, "SIGN_IN", "{}"), "$.data.challengeId");
            String code = sendAndCapture(challenge, "SMS", install, msisdn);
            for (int i = 0; i < 2; i++) {
                mvc.perform(broker(post("/auth/client-service/otp/verify"), install)
                        .content("{\"challengeId\":\"" + challenge + "\",\"otp\":\"" + wrong(code) + "\"}"))
                        .andExpect(status().isUnauthorized());
            }
            mvc.perform(broker(post("/auth/client-service/otp/verify"), install)
                            .content("{\"challengeId\":\"" + challenge + "\",\"otp\":\"" + wrong(code) + "\"}"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.data.attemptsLeft").value(0));
        }

        String blocked = clientService(msisdn, install, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(blocked, "$.data.decision")).isEqualTo("TEMP_BLOCKED");
        assertThat((String) JsonPath.read(blocked, "$.data.reason")).isEqualTo("OTP_ATTEMPTS");
        String ref = JsonPath.read(blocked, "$.data.supportRef");
        assertThat((String) JsonPath.read(blocked, "$.message")).contains("paused until").contains(ref);
        verify(sms, timeout(5000)).sendSms(eq(msisdn), contains("is paused until"), anyString());

        // *569#: the paused phone is listed and can be unlocked.
        String list = ussd("/device-security/ussd/devices/blocked", "{\"msisdn\":\"" + msisdn + "\"}");
        assertThat((Boolean) JsonPath.read(list, "$.data.devices[0].eligible")).isTrue();
        assertThat((String) JsonPath.read(list, "$.data.menuText")).startsWith("Choose the phone to unlock:\n1. ");
        String deviceId = JsonPath.read(list, "$.data.devices[0].deviceId");

        String unlocked = ussd("/device-security/ussd/devices/unlock", "{\"msisdn\":\"" + msisdn
                + "\",\"deviceId\":\"" + deviceId + "\",\"ussdSessionId\":\"ussd-1\"}");
        assertThat((String) JsonPath.read(unlocked, "$.data.result")).isEqualTo("UNLOCKED");
        verify(sms, timeout(5000)).sendSms(eq(msisdn), contains("is unlocked"), anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = 'DEVICE_SECURITY_UNLOCKED' "
                + "AND target_id = ?", Integer.class, deviceId)).isEqualTo(1);

        String after = clientService(msisdn, install, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(after, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        assertThat((String) JsonPath.read(after, "$.data.reason")).isEqualTo("UNLOCKED");
    }

    @Test
    @DisplayName("*569# Block device: a lost phone is cut off at its next renewal; one USSD unlock per 7 days")
    void ussdBlock_lostPhone() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        trustedPhone(msisdn, install);

        String active = ussd("/device-security/ussd/devices/active", "{\"msisdn\":\"" + msisdn + "\"}");
        String deviceId = JsonPath.read(active, "$.data.devices[0].deviceId");
        String blocked = ussd("/device-security/ussd/devices/block", "{\"msisdn\":\"" + msisdn + "\",\"deviceId\":\""
                + deviceId + "\",\"ussdSessionId\":\"ussd-2\"}");
        assertThat((String) JsonPath.read(blocked, "$.data.result")).isEqualTo("BLOCKED");
        verify(sms, timeout(5000)).sendSms(eq(msisdn), contains("You blocked your"), anyString());

        String renew = clientService(msisdn, install, "RENEW", "{}");
        assertThat((String) JsonPath.read(renew, "$.data.decision")).isEqualTo("BANNED");
        assertThat((Boolean) JsonPath.read(renew, "$.data.ussdUnlock")).isTrue();
        assertThat((String) JsonPath.read(renew, "$.data.ussdCode")).isEqualTo("*569#");

        // Someone else's number cannot touch this phone.
        String foreign = ussd("/device-security/ussd/devices/unlock", "{\"msisdn\":\"" + number()
                + "\",\"deviceId\":\"" + deviceId + "\",\"ussdSessionId\":\"ussd-3\"}");
        assertThat((String) JsonPath.read(foreign, "$.data.result")).isEqualTo("NOT_FOUND");

        String unlock = "{\"msisdn\":\"" + msisdn + "\",\"deviceId\":\"" + deviceId + "\",\"ussdSessionId\":\"ussd-4\"}";
        assertThat((String) JsonPath.read(ussd("/device-security/ussd/devices/unlock", unlock), "$.data.result"))
                .isEqualTo("UNLOCKED");

        // Blocked again within 7 days of an unlock: support only (§9.3).
        ussd("/device-security/ussd/devices/block", "{\"msisdn\":\"" + msisdn + "\",\"deviceId\":\"" + deviceId
                + "\",\"ussdSessionId\":\"ussd-5\"}");
        String again = ussd("/device-security/ussd/devices/unlock", unlock);
        assertThat((String) JsonPath.read(again, "$.data.result")).isEqualTo("NOT_ELIGIBLE");
        assertThat((String) JsonPath.read(again, "$.data.menuText")).contains("only be unlocked by InnBucks support");

        // Three unlock attempts a day, refusals included: this is the third, the fourth is refused.
        assertThat((String) JsonPath.read(ussd("/device-security/ussd/devices/unlock", unlock), "$.data.result"))
                .isEqualTo("NOT_ELIGIBLE");
        String fourth = ussd("/device-security/ussd/devices/unlock", unlock);
        assertThat((String) JsonPath.read(fourth, "$.data.result")).isEqualTo("TRY_LATER");
    }

    @Test
    @DisplayName("a hooking framework bans; root is an integrity hold that lifts once the phone is clean")
    void integrityFindings() throws Exception {
        String hooked = clientService(number(), UUID.randomUUID().toString(), "SIGN_IN",
                "{\"source\":\"freerasp\",\"threats\":[\"hooks\"]}");
        assertThat((String) JsonPath.read(hooked, "$.data.decision")).isEqualTo("BANNED");
        assertThat((String) JsonPath.read(hooked, "$.data.reason")).isEqualTo("INTEGRITY");

        String msisdn = number();
        String install = UUID.randomUUID().toString();
        String rooted = clientService(msisdn, install, "SIGN_IN", "{\"source\":\"freerasp\",\"threats\":[\"privilegedAccess\"]}");
        assertThat((String) JsonPath.read(rooted, "$.data.decision")).isEqualTo("TEMP_BLOCKED");
        assertThat((String) JsonPath.read(rooted, "$.data.reason")).isEqualTo("INTEGRITY_HOLD");
        assertThat((Object) JsonPath.read(rooted, "$.data.blockedUntil")).isNull();

        String clean = clientService(msisdn, install, "SIGN_IN", "{\"source\":\"freerasp\",\"threats\":[]}");
        assertThat((String) JsonPath.read(clean, "$.data.decision")).isEqualTo("OTP_REQUIRED");
    }

    @Test
    @DisplayName("a third account on one phone bans the phone for everyone; only the fraud desk lifts it")
    void sharedDevice_isFraudDeskOnly() throws Exception {
        String install = UUID.randomUUID().toString();
        trustedPhone(number(), install);
        trustedPhone(number(), install);
        String third = number();
        String res = clientService(third, install, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(res, "$.data.decision")).isEqualTo("BANNED");
        assertThat((String) JsonPath.read(res, "$.data.reason")).isEqualTo("SHARED_DEVICE");
        assertThat((Boolean) JsonPath.read(res, "$.data.ussdUnlock")).isFalse();
        assertThat((String) JsonPath.read(res, "$.message")).contains("0867 700 0000");

        String deviceId = jdbc.queryForObject("SELECT public_id::text FROM customer_devices WHERE msisdn = ?",
                String.class, third);
        mvc.perform(post("/admin/device-security/devices/" + deviceId + "/unlock")
                        .with(user("agent@innbucks.co.zw").authorities(new SimpleGrantedAuthority("device-security:manage")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"caller says it is theirs\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("fraud_desk_required"));
        mvc.perform(post("/admin/device-security/devices/" + deviceId + "/unlock")
                        .with(user("fraud@innbucks.co.zw").authorities(new SimpleGrantedAuthority("device-security:manage"),
                                new SimpleGrantedAuthority("device-security:fraud")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"FR-2026-0412 cleared\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.device.state").value("STEP_UP"))
                .andExpect(jsonPath("$.data.device.deviceWideBan").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("a correct OTP followed by a PIN lock-out blocks the phone (SIM without PIN, §8.4)")
    void lockedAfterOtp_blocks() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        String challenge = JsonPath.read(clientService(msisdn, install, "SIGN_IN", "{}"), "$.data.challengeId");
        String ticket = JsonPath.read(verifyOtp(challenge, sendAndCapture(challenge, "WHATSAPP", install, msisdn), install),
                "$.data.loginTicket");
        loginResult(jti(ticket), "LOCKED").andExpect(jsonPath("$.data.deviceState").value("TEMP_BLOCKED"));
        String next = clientService(msisdn, install, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(next, "$.data.reason")).isEqualTo("WRONG_PINS");
    }

    @Test
    @DisplayName("more than 5 OTP challenges for one number in an hour is a VELOCITY block (§8.4, §11)")
    void otpVelocity_blocks() throws Exception {
        String msisdn = number();
        for (int i = 0; i < 5; i++) {
            assertThat((String) JsonPath.read(clientService(msisdn, UUID.randomUUID().toString(), "SIGN_IN", "{}"),
                    "$.data.decision")).isEqualTo("OTP_REQUIRED");
        }
        String sixth = clientService(msisdn, UUID.randomUUID().toString(), "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(sixth, "$.data.decision")).isEqualTo("TEMP_BLOCKED");
        assertThat((String) JsonPath.read(sixth, "$.data.reason")).isEqualTo("VELOCITY");
    }

    // =====================================================================================
    // Your devices and the in-session step-up
    // =====================================================================================

    @Test
    @DisplayName("Your devices lists the phones and removes one; the removed phone's renewal needs a code")
    void yourDevices_listAndRemove() throws Exception {
        String msisdn = number();
        String current = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        trustedPhone(msisdn, current);
        trustedPhone(msisdn, other);
        String auth = customer(msisdn);

        String list = mvc.perform(get("/auth/devices").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", current))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> devices = JsonPath.read(list, "$.data.devices");
        assertThat(devices).hasSize(2);
        assertThat(list).doesNotContain(current).doesNotContain(other); // never an install id
        String otherId = devices.stream().filter(d -> !(Boolean) d.get("current")).findFirst().orElseThrow()
                .get("deviceId").toString();

        mvc.perform(delete("/auth/devices/" + otherId).header("Authorization", auth).header("x-api-key", BROKER_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(true));
        verify(whatsApp, timeout(5000).atLeastOnce()).sendCustomNotification(eq(msisdn), contains("was removed"));

        String renew = clientService(msisdn, other, "RENEW", "{}");
        assertThat((String) JsonPath.read(renew, "$.data.decision")).isEqualTo("OTP_REQUIRED");

        // Not signed in as a customer: the security chain's 401.
        mvc.perform(get("/auth/devices").header("x-api-key", BROKER_KEY)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a phone bound minutes ago must re-prove possession before a large transfer (§5.5)")
    void sessionStepUp() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        trustedPhone(msisdn, install);
        String auth = customer(msisdn);

        String res = mvc.perform(post("/auth/device/challenge").header("Authorization", auth)
                        .header("x-api-key", BROKER_KEY).header("x-device-id", install)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"LARGE_TRANSFER\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(res, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        String challenge = JsonPath.read(res, "$.data.challengeId");

        // A session challenge is not a sign-in challenge.
        mvc.perform(broker(post("/auth/client-service/otp/send"), install)
                        .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"WHATSAPP\"}"))
                .andExpect(status().isGone());

        mvc.perform(post("/auth/device/challenge/send").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", install).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"WHATSAPP\"}"))
                .andExpect(status().isOk());
        String code = lastCode(msisdn);
        String verified = mvc.perform(post("/auth/device/challenge/verify").header("Authorization", auth)
                        .header("x-api-key", BROKER_KEY).header("x-device-id", install)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challenge + "\",\"otp\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(verified, "$.data.action")).isEqualTo("LARGE_TRANSFER");
        String proof = JsonPath.read(verified, "$.data.stepUpProof");
        assertThat(proof.split("\\.")).hasSize(3);
    }

    // =====================================================================================
    // The call-center console
    // =====================================================================================

    @Test
    @DisplayName("the call center finds a caller by number or reference, blocks a lost phone, and it is audited")
    void console_lookUpAndBlock() throws Exception {
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        trustedPhone(msisdn, install);
        var reader = user("agent@innbucks.co.zw").authorities(new SimpleGrantedAuthority("device-security:read"));
        var manager = user("agent@innbucks.co.zw").authorities(new SimpleGrantedAuthority("device-security:read"),
                new SimpleGrantedAuthority("device-security:manage"));

        String overview = mvc.perform(get("/admin/device-security/customers/" + msisdn.replace("+263", "0")).with(reader))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(overview, "$.data.msisdn")).isEqualTo(msisdn);
        assertThat((String) JsonPath.read(overview, "$.data.devices[0].state")).isEqualTo("TRUSTED");
        assertThat((String) JsonPath.read(overview, "$.data.devices[0].agentGuidance")).startsWith("Signed in normally");
        String deviceId = JsonPath.read(overview, "$.data.devices[0].deviceId");

        String blockBody = "{\"mode\":\"UNTIL_UNLOCKED\",\"note\":\"Caller reported the phone stolen; verified DOB.\"}";
        mvc.perform(post("/admin/device-security/devices/" + deviceId + "/block").with(reader)
                        .contentType(MediaType.APPLICATION_JSON).content(blockBody))
                .andExpect(status().isForbidden());
        String blocked = mvc.perform(post("/admin/device-security/devices/" + deviceId + "/block").with(manager)
                        .contentType(MediaType.APPLICATION_JSON).content(blockBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(blocked, "$.data.device.state")).isEqualTo("BANNED");
        assertThat((String) JsonPath.read(blocked, "$.data.device.stateReason")).isEqualTo("CUSTOMER_REPORTED");
        String ref = JsonPath.read(blocked, "$.data.device.supportRef");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = 'DEVICE_SECURITY_BANNED' "
                + "AND actor_id = 'agent@innbucks.co.zw' AND target_id = ?", Integer.class, deviceId)).isEqualTo(1);

        mvc.perform(get("/admin/device-security/support-refs/" + ref.toLowerCase().replace("sec-", "")).with(reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.msisdn").value(msisdn))
                .andExpect(jsonPath("$.data.device.deviceId").value(deviceId));

        mvc.perform(get("/admin/device-security/devices").param("state", "BANNED").with(reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].deviceId").value(deviceId));

        mvc.perform(get("/admin/device-security/customers/" + msisdn + "/events").with(reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].type").value("DEVICE_BANNED"));

        // Unauthenticated: the security chain's 401, not a controller answer.
        mvc.perform(get("/admin/device-security/customers/" + msisdn)).andExpect(status().isUnauthorized());
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    /** A phone taken all the way to TRUSTED; returns the final TOKEN body. */
    private String trustedPhone(String msisdn, String install) throws Exception {
        String challenge = JsonPath.read(clientService(msisdn, install, "SIGN_IN", "{}"), "$.data.challengeId");
        String token = verifyOtp(challenge, sendAndCapture(challenge, "WHATSAPP", install, msisdn), install);
        loginResult(jti(JsonPath.read(token, "$.data.loginTicket")), "SUCCESS");
        return clientService(msisdn, install, "SIGN_IN", "{}");
    }

    private String clientService(String msisdn, String install, String context, String integrity) throws Exception {
        MvcResult r = mvc.perform(broker(post("/auth/client-service"), install)
                        .content(signInBody(msisdn, install, context, integrity)))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String signInBody(String msisdn, String install, String context, String integrity) {
        return """
                {"requestId":"%s","purpose":"SIGN_IN","context":"%s","msisdn":"%s",
                 "device":{"installId":"%s","platform":"android","osVersion":"15","model":"SM-A155F","manufacturer":"samsung","appVersion":"2.4.0"},
                 "integrity":%s,
                 "location":{"status":"GRANTED","lat":-17.825,"lng":31.053,"accuracyM":35,"mocked":false}}
                """.formatted(UUID.randomUUID(), context, msisdn, install,
                "{}".equals(integrity) ? "{\"source\":\"freerasp\",\"threats\":[]}" : integrity);
    }

    private String sendAndCapture(String challenge, String channel, String install, String msisdn) throws Exception {
        mvc.perform(broker(post("/auth/client-service/otp/send"), install)
                        .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"" + channel + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value(channel));
        return "SMS".equals(channel) ? lastSmsCode(msisdn) : lastCode(msisdn);
    }

    private String lastCode(String msisdn) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(whatsApp, atLeastOnce()).sendCustomNotification(eq(msisdn), text.capture());
        return code(text.getAllValues());
    }

    private String lastSmsCode(String msisdn) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sms, atLeastOnce()).sendSms(eq(msisdn), text.capture(), anyString());
        return code(text.getAllValues());
    }

    private static String code(List<String> messages) {
        Pattern p = Pattern.compile("InnBucks code:? (\\d{6})");
        for (int i = messages.size() - 1; i >= 0; i--) {
            Matcher m = p.matcher(messages.get(i));
            if (m.find()) return m.group(1);
        }
        throw new AssertionError("no OTP was sent: " + messages);
    }

    private String verifyOtp(String challenge, String code, String install) throws Exception {
        return mvc.perform(broker(post("/auth/client-service/otp/verify"), install)
                        .content("{\"challengeId\":\"" + challenge + "\",\"otp\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.ResultActions redeem(String ticket, String msisdn, String install,
                                                                      String purpose) throws Exception {
        return mvc.perform(post("/device-security/broker/tickets/redeem").header("x-api-key", BROKER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":\"" + ticket + "\",\"msisdn\":\"" + msisdn + "\",\"installId\":\"" + install
                                + "\",\"purpose\":\"" + purpose + "\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions loginResult(String ticketId, String outcome) throws Exception {
        return mvc.perform(post("/device-security/broker/login-result").header("x-api-key", BROKER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":\"" + ticketId + "\",\"outcome\":\"" + outcome + "\",\"stagingCode\":\"000\"}"))
                .andExpect(status().isOk());
    }

    private String ussd(String path, String body) throws Exception {
        return mvc.perform(post(path).header("x-api-key", USSD_KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequestBuilder broker(MockHttpServletRequestBuilder b, String install) {
        b.header("x-api-key", BROKER_KEY).header("X-Forwarded-For", "41.221.147.12").contentType(MediaType.APPLICATION_JSON);
        if (install != null) b.header("x-device-id", install);
        return b;
    }

    private static String jti(String jws) {
        String payload = new String(Base64.getUrlDecoder().decode(jws.split("\\.")[1]), StandardCharsets.UTF_8);
        return JsonPath.read(payload, "$.jti");
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    /** A fresh valid Econet number per call, so tests never share a registry row. */
    private static String number() {
        return "+26377" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    /**
     * A REAL fleet CUSTOMER session for this number, minted the way /auth/exchange mints one and sent as a
     * Bearer header — so the request goes through JwtFilter exactly as production does (injecting an
     * Authentication directly would hide a filter that never reads the token).
     */
    private String customer(String msisdn) {
        User u = users.save(User.builder().firstName("Tariro").lastName("Moyo").phoneNumber(msisdn)
                .password("{noop}unused").roles(User.roleNames(User.Role.CUSTOMER)).active(true).approved(true).build());
        return "Bearer " + jwtUtil.generateToken(msisdn, List.of("CUSTOMER"), List.of(), List.of(), 1, false, msisdn,
                null, null, "Tariro", null, "Moyo", u.getTokenVersion(), "ZW", u.getUserUuid(), null, false);
    }
}
