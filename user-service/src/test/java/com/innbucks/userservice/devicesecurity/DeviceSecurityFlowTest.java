package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
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

    /** The app lines and notice copy one phone per number pins, verbatim. */
    static final String OTP_LINE = "Let's confirm it's you on this phone.";
    static final String TOKEN_LINE = "Enter your PIN to continue.";
    static final String TOKEN_LINE_SIGNING_OUT = "Enter your PIN to continue. Any other phone will be signed out of InnBucks.";
    static final String PHONE_REPLACED = "is now signed in only on";
    static final String NOT_SIGNED_IN = "This phone isn't signed in to your InnBucks. Please sign in again.";

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
    @Autowired MarketTimeZone marketTimeZone;

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
        // Every flow runs with one phone per number on; the tests of today's behaviour switch it off first.
        properties.getTrust().setSingleDevice(true);
        when(staging.isConfigured()).thenReturn(true);
        when(staging.token()).thenReturn(new StagingClientServiceClient.ClientServiceToken("staging-client-token",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)));
    }

    @AfterEach
    void restoreTheShippedDefault() {
        // The properties bean is a context singleton: leave it as the application ships it.
        properties.getTrust().setSingleDevice(false);
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
        // With one phone per number on, a sign-in code says what using it does.
        assertThat(lastOtpText(msisdn)).isEqualTo("InnBucks code: " + code + ". It expires in 5 minutes. Never share "
                + "it — InnBucks will never ask for it. Using it signs InnBucks out on any other phone.");

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
        // No other phone of the number is in use, so the PIN screen warns of nothing.
        assertThat((String) JsonPath.read(token, "$.message")).isEqualTo(TOKEN_LINE);
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
    @DisplayName("Your devices lists the phones and removes one; the removed phone's renewal needs a code "
            + "(one phone per number off: today's behaviour)")
    void yourDevices_listAndRemove() throws Exception {
        properties.getTrust().setSingleDevice(false);
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
    // One phone per number (trust.single-device)
    // =====================================================================================

    @Test
    @DisplayName("one phone per number: a second phone that proves the SIM and then the PIN signs the first out, "
            + "every channel hears it, and only the signed-in phone can manage phones")
    void singleDevice_secondPhoneReplacesTheFirst() throws Exception {
        String msisdn = number();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        String firstTicket = JsonPath.read(trustedPhone(msisdn, first), "$.data.loginTicket");
        String firstId = publicId(msisdn, first);
        forgetMessagesAfterNewPhoneNotices(msisdn, 1);

        // Asking for a code says nothing about another phone: whoever knows the number learns nothing.
        String asked = clientService(msisdn, second, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(asked, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        assertThat((String) JsonPath.read(asked, "$.data.reason")).isEqualTo("NEW_DEVICE");
        assertThat((String) JsonPath.read(asked, "$.message")).isEqualTo(OTP_LINE);
        String challenge = JsonPath.read(asked, "$.data.challengeId");

        // The code itself says what using it does (a customer reading it to a fake agent signs their own phone out).
        String code = sendAndCapture(challenge, "WHATSAPP", second, msisdn);
        assertThat(lastOtpText(msisdn)).isEqualTo("InnBucks code: " + code + ". It expires in 5 minutes. Never share "
                + "it — InnBucks will never ask for it. Using it signs InnBucks out on any other phone.");

        // SIM proved: the PIN screen warns that the PIN signs the other phone out.
        String token = verifyOtp(challenge, code, second);
        assertThat((String) JsonPath.read(token, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(token, "$.message")).isEqualTo(TOKEN_LINE_SIGNING_OUT);
        assertThat((String) JsonPath.read(token, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        // Nothing is signed out before the PIN is proven too.
        assertThat(state(msisdn, first)).isEqualTo("TRUSTED");

        // A mistyped PIN, then a retry inside the PIN grace: the retry's PIN screen repeats the warning.
        loginResult(jti(JsonPath.read(token, "$.data.loginTicket")), "WRONG_PIN")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("PENDING_PIN"));
        String retry = clientService(msisdn, second, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(retry, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(retry, "$.message")).isEqualTo(TOKEN_LINE_SIGNING_OUT);
        assertThat((String) JsonPath.read(retry, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        loginResult(jti(JsonPath.read(retry, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));
        String secondId = publicId(msisdn, second);

        // The first phone is signed out with nothing left that lets it back in without a code...
        assertReplaced(msisdn, first);
        // ...the ticket it still held dies with it...
        assertThat(jdbc.queryForObject("SELECT voided_at IS NOT NULL FROM device_login_tickets WHERE jti = ?",
                Boolean.class, jti(firstTicket))).isTrue();
        // ...and the log says which phone signed it out.
        List<Map<String, Object>> revoked = jdbc.queryForList("SELECT actor_type, reason, note FROM device_security_events "
                + "WHERE event_type = 'DEVICE_REVOKED' AND device_id = ?", UUID.fromString(firstId));
        assertThat(revoked).hasSize(1);
        assertThat(revoked.get(0)).containsEntry("actor_type", "SYSTEM").containsEntry("reason", "REPLACED")
                .containsEntry("note", "Signed out by phone " + secondId);

        // The customer hears it once on EVERY channel, SMS first — never as "a new phone" or "removed".
        verify(whatsApp, timeout(5000)).sendCustomNotification(eq(msisdn), contains(PHONE_REPLACED));
        ArgumentCaptor<String> smsText = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> smsRef = ArgumentCaptor.forClass(String.class);
        verify(sms, after(500).times(1)).sendSms(eq(msisdn), smsText.capture(), smsRef.capture());
        assertThat(smsText.getValue()).matches(Pattern.quote("InnBucks is now signed in only on Samsung SM-A155F, since ")
                + "\\d{2}\\.\\d{2}( on \\d{1,2} [A-Z][a-z]{2})?" + Pattern.quote(". If this wasn't you, dial star 569 hash now."));
        assertThat(smsRef.getValue()).startsWith("DTX-PHONE_REPLACED-");
        ArgumentCaptor<String> waText = ArgumentCaptor.forClass(String.class);
        verify(whatsApp, times(1)).sendCustomNotification(eq(msisdn), contains(PHONE_REPLACED));
        verify(whatsApp, atLeastOnce()).sendCustomNotification(eq(msisdn), waText.capture());
        assertThat(waText.getAllValues().stream().filter(t -> t.contains(PHONE_REPLACED)).toList()).singleElement()
                .asString().matches(Pattern.quote("InnBucks is now signed in only on Samsung SM-A155F, since ")
                        + "\\d{2}:\\d{2}( on \\d{1,2} [A-Z][a-z]{2})?" + Pattern.quote(". If this wasn't you, dial *569# "
                        + "now, or call InnBucks support on 0867 700 0000 if your SIM isn't working."));
        InOrder smsFirst = inOrder(sms, whatsApp);
        smsFirst.verify(sms).sendSms(eq(msisdn), contains(PHONE_REPLACED), anyString());
        smsFirst.verify(whatsApp).sendCustomNotification(eq(msisdn), contains(PHONE_REPLACED));
        verify(sms, after(500).never()).sendSms(eq(msisdn), contains("A new phone"), anyString());
        verify(whatsApp, after(500).never()).sendCustomNotification(eq(msisdn), contains("A new phone"));
        verify(sms, after(500).never()).sendSms(eq(msisdn), contains("was removed"), anyString());
        verify(whatsApp, after(500).never()).sendCustomNotification(eq(msisdn), contains("was removed"));

        // The signed-out phone's next renewal needs a code, with the ordinary line; it is NEW again, still marked
        // REPLACED, and the fraud desk can tell this phone switch from other first sign-ins.
        String renew = clientService(msisdn, first, "RENEW", "{}");
        assertThat((String) JsonPath.read(renew, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        assertThat((String) JsonPath.read(renew, "$.message")).isEqualTo(OTP_LINE);
        Map<String, Object> asking = row(msisdn, first);
        assertThat(asking.get("state")).isEqualTo("NEW");
        assertThat(asking.get("state_reason")).isEqualTo("REPLACED");
        assertThat(jdbc.queryForObject("SELECT features FROM device_security_events WHERE device_id = ? "
                        + "AND event_type = 'SIGN_IN_DECISION' ORDER BY id DESC LIMIT 1", String.class, UUID.fromString(firstId)))
                .contains("\"replacedPhone\":true");

        // Your devices: the signed-in phone sees only itself...
        String auth = customer(msisdn);
        String list = mvc.perform(get("/auth/devices").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", second))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> devices = JsonPath.read(list, "$.data.devices");
        assertThat(devices).hasSize(1);
        assertThat(devices.get(0)).containsEntry("deviceId", secondId).containsEntry("current", true)
                .containsEntry("state", "TRUSTED").containsEntry("status", "Signed in");
        assertThat((Boolean) JsonPath.read(list, "$.data.tidyUpSuggested")).isFalse();

        // ...the signed-out phone, still holding a fleet session, can neither list nor remove phones...
        mvc.perform(get("/auth/devices").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", first))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("device_not_trusted"))
                .andExpect(jsonPath("$.message").value(NOT_SIGNED_IN));
        mvc.perform(delete("/auth/devices/" + secondId).header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", first))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("device_not_trusted"));
        assertThat(state(msisdn, second)).isEqualTo("TRUSTED");

        // ...and a call that names no phone at all is refused before anything is read.
        mvc.perform(get("/auth/devices").header("Authorization", auth).header("x-api-key", BROKER_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("invalid_request"))
                .andExpect(jsonPath("$.data.field").value("x-device-id"))
                .andExpect(jsonPath("$.message").value("The x-device-id header is required."));
        mvc.perform(delete("/auth/devices/" + secondId).header("Authorization", auth).header("x-api-key", BROKER_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("invalid_request"))
                .andExpect(jsonPath("$.data.field").value("x-device-id"));
        assertThat(state(msisdn, second)).isEqualTo("TRUSTED");
    }

    @Test
    @DisplayName("one phone per number: a ticket the signed-out phone still holds is refused INVALID at redeem, even "
            + "one that escaped its void, and its late SUCCESS binds nothing")
    void singleDevice_aTicketForAReplacedPhoneIsRefused() throws Exception {
        String msisdn = number();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        trustedPhone(msisdn, first);
        String signIn = clientService(msisdn, first, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(signIn, "$.data.decision")).isEqualTo("TOKEN");
        String ticket = JsonPath.read(signIn, "$.data.loginTicket");

        bind(msisdn, second);

        redeem(ticket, msisdn, first, "SIGN_IN")
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.reason").value("INVALID"));

        // A ticket minted just after the sign-out escapes its void (the TOKEN is minted outside the decision's
        // transaction), so redeem checks the phone itself — and voids the ticket it refuses.
        jdbc.update("UPDATE device_login_tickets SET voided_at = NULL WHERE jti = ?", jti(ticket));
        redeem(ticket, msisdn, first, "SIGN_IN")
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.reason").value("INVALID"));
        assertThat(jdbc.queryForObject("SELECT voided_at IS NOT NULL FROM device_login_tickets WHERE jti = ?",
                Boolean.class, jti(ticket))).isTrue();
        // The broker sees the reason it already handles; the log says why.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_security_events WHERE event_type = 'TICKET_REJECTED' "
                + "AND reason = 'INVALID' AND note = ?", Integer.class, "ticket " + jti(ticket) + ": phone no longer signed in"))
                .isEqualTo(2);

        // The broker's late SUCCESS is recorded, and binds nothing.
        loginResult(jti(ticket), "SUCCESS")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("REVOKED"));
        assertThat(state(msisdn, first)).isEqualTo("REVOKED");
        assertThat(state(msisdn, second)).isEqualTo("TRUSTED");
    }

    @Test
    @DisplayName("one phone per number: the signed-out phone comes back with a code and the PIN, signs the other out "
            + "in turn, and is treated as a first bind again")
    void singleDevice_aReturningPhoneReplacesBack() throws Exception {
        String msisdn = number();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        trustedPhone(msisdn, first);
        String auth = customer(msisdn);
        // Bound three days ago: past the session step-up window, so a large transfer needs no extra code.
        jdbc.update("UPDATE customer_devices SET bound_at = ? WHERE msisdn = ? AND install_id_hash = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(3), msisdn, DeviceIdentity.hashInstallId(first));
        assertThat((String) JsonPath.read(stepUp(auth, first, "{\"action\":\"LARGE_TRANSFER\"}"), "$.data.decision"))
                .isEqualTo("NOT_REQUIRED");
        forgetMessagesAfterNewPhoneNotices(msisdn, 1);

        bind(msisdn, second);
        expectPhoneReplacedNotices(msisdn, 1);
        assertReplaced(msisdn, first);

        // Back on the first phone: a code, then the PIN — and the PIN screen warns again.
        String asked = clientService(msisdn, first, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(asked, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        assertThat((String) JsonPath.read(asked, "$.message")).isEqualTo(OTP_LINE);
        String challenge = JsonPath.read(asked, "$.data.challengeId");
        String token = verifyOtp(challenge, sendAndCapture(challenge, "WHATSAPP", first, msisdn), first);
        assertThat((String) JsonPath.read(token, "$.message")).isEqualTo(TOKEN_LINE_SIGNING_OUT);
        loginResult(jti(JsonPath.read(token, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));

        Map<String, Object> back = row(msisdn, first);
        assertThat(back.get("state")).isEqualTo("TRUSTED");
        assertThat(back.get("state_reason")).isNull();
        assertThat(back.get("cooling_until")).isNotNull();
        assertThat(((Timestamp) back.get("bound_at")).toLocalDateTime())
                .isAfter(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        assertReplaced(msisdn, second);

        // The customer is told again on every channel, and never that "a new phone" signed in.
        expectPhoneReplacedNotices(msisdn, 2);
        verify(sms, after(500).never()).sendSms(eq(msisdn), contains("A new phone"), anyString());
        verify(whatsApp, after(500).never()).sendCustomNotification(eq(msisdn), contains("A new phone"));

        // A first bind again: the session step-up window runs from now, so the same transfer now needs a code.
        assertThat((String) JsonPath.read(stepUp(auth, first, "{\"action\":\"LARGE_TRANSFER\"}"), "$.data.decision"))
                .isEqualTo("OTP_REQUIRED");
    }

    @Test
    @DisplayName("one phone per number: a phone trusted without a code (watch mode) signs no phone out")
    void singleDevice_aProvisionalBindSignsNoPhoneOut() throws Exception {
        String msisdn = number();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        trustedPhone(msisdn, first);
        properties.getEnforce().setOtp(false);
        properties.getEnforce().setBlocks(false);
        properties.getEnforce().setBans(false);

        String token = clientService(msisdn, second, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(token, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(token, "$.message")).isEqualTo(TOKEN_LINE);
        assertThat((String) JsonPath.read(token, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        loginResult(jti(JsonPath.read(token, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));

        assertThat(state(msisdn, first)).isEqualTo("TRUSTED");
        assertThat(state(msisdn, second)).isEqualTo("TRUSTED");
        expectNoPhoneReplacedNotice(msisdn);
    }

    @Test
    @DisplayName("one phone per number: with codes off, a handset's months-old proof never signs the current phone "
            + "out on the PIN alone")
    void singleDevice_oldProofWithOtpOff_signsNoPhoneOut() throws Exception {
        properties.getTrust().setSingleDevice(false);
        String msisdn = number();
        String old = UUID.randomUUID().toString();
        String current = UUID.randomUUID().toString();
        trustedPhone(msisdn, old);
        trustedPhone(msisdn, current);
        jdbc.update("UPDATE customer_devices SET trusted_until = ? WHERE msisdn = ? AND install_id_hash = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1), msisdn, DeviceIdentity.hashInstallId(old));
        properties.getTrust().setSingleDevice(true);
        properties.getEnforce().setOtp(false);

        // Trust expired and codes off: TOKEN on the PIN alone, its SIM proof predating this sign-in.
        String token = clientService(msisdn, old, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(token, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(token, "$.message")).isEqualTo(TOKEN_LINE);
        assertThat((String) JsonPath.read(token, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        loginResult(jti(JsonPath.read(token, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));

        assertThat(state(msisdn, old)).isEqualTo("TRUSTED");
        assertThat(state(msisdn, current)).isEqualTo("TRUSTED");
        expectNoPhoneReplacedNotice(msisdn);
    }

    @Test
    @DisplayName("one phone per number: switching it on signs nobody out; a number's phones from before converge at "
            + "the next phone that proves the SIM, with one notice")
    void singleDevice_numbersFromBeforeTheSwitchConvergeAtTheNextProvedBind() throws Exception {
        properties.getTrust().setSingleDevice(false);
        String msisdn = number();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        trustedPhone(msisdn, a);
        trustedPhone(msisdn, b);
        properties.getTrust().setSingleDevice(true);

        // No sweep: a renewal under the rule is still silent, and both phones stay signed in.
        String renew = clientService(msisdn, a, "RENEW", "{}");
        assertThat((String) JsonPath.read(renew, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(renew, "$.message")).isEqualTo(TOKEN_LINE);
        assertThat((String) JsonPath.read(renew, "$.data.trust.state")).isEqualTo("TRUSTED");
        assertThat(state(msisdn, a)).isEqualTo("TRUSTED");
        assertThat(state(msisdn, b)).isEqualTo("TRUSTED");
        forgetMessagesAfterNewPhoneNotices(msisdn, 2);

        bind(msisdn, c);

        assertReplaced(msisdn, a);
        assertReplaced(msisdn, b);
        assertThat(state(msisdn, c)).isEqualTo("TRUSTED");
        String note = "Signed out by phone " + publicId(msisdn, c);
        assertThat(jdbc.queryForList("SELECT note FROM device_security_events WHERE event_type = 'DEVICE_REVOKED' "
                + "AND msisdn = ?", String.class, msisdn)).containsExactly(note, note);
        expectPhoneReplacedNotices(msisdn, 1);
    }

    @Test
    @DisplayName("one phone per number: a paused, a blocked and a never-confirmed phone are left exactly as they are, "
            + "and the customer is told of a new phone, not a replaced one")
    void singleDevice_blockedBannedAndNeverConfirmedPhonesAreUntouched() throws Exception {
        properties.getTrust().setSingleDevice(false);
        String msisdn = number();
        String paused = UUID.randomUUID().toString();
        String blocked = UUID.randomUUID().toString();
        String asking = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        trustedPhone(msisdn, paused);
        trustedPhone(msisdn, blocked);

        // The call center pauses one phone...
        String pause = mvc.perform(post("/admin/device-security/devices/" + publicId(msisdn, paused) + "/block")
                        .with(agent("device-security:read", "device-security:manage"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"TEMPORARY\",\"durationMinutes\":60,\"note\":\"Caller asked for a pause while they check the phone.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.device.state").value("TEMP_BLOCKED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String pausedRef = JsonPath.read(pause, "$.data.device.supportRef");
        // ...the customer blocks another on *569#...
        String block = ussd("/device-security/ussd/devices/block", "{\"msisdn\":\"" + msisdn + "\",\"deviceId\":\""
                + publicId(msisdn, blocked) + "\",\"ussdSessionId\":\"ussd-g1\"}");
        assertThat((String) JsonPath.read(block, "$.data.result")).isEqualTo("BLOCKED");
        String blockedRef = JsonPath.read(block, "$.data.supportRef");
        // ...and a third phone asks for a code and never finishes.
        String asked = clientService(msisdn, asking, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(asked, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        String askingChallenge = JsonPath.read(asked, "$.data.challengeId");

        properties.getTrust().setSingleDevice(true);
        verify(whatsApp, timeout(5000).times(2)).sendCustomNotification(eq(msisdn), contains("A new phone"));
        verify(whatsApp, timeout(5000)).sendCustomNotification(eq(msisdn), contains("is paused until"));
        verify(sms, timeout(5000)).sendSms(eq(msisdn), contains("You blocked your"), anyString());
        clearInvocations(whatsApp, sms);

        bind(msisdn, fresh);

        Map<String, Object> p = row(msisdn, paused);
        assertThat(p.get("state")).isEqualTo("TEMP_BLOCKED");
        assertThat(p.get("state_reason")).isEqualTo("SUPPORT");
        assertThat(p.get("support_ref")).isEqualTo(pausedRef);
        Map<String, Object> x = row(msisdn, blocked);
        assertThat(x.get("state")).isEqualTo("BANNED");
        assertThat(x.get("state_reason")).isEqualTo("CUSTOMER_REPORTED");
        assertThat(x.get("support_ref")).isEqualTo(blockedRef);
        Map<String, Object> w = row(msisdn, asking);
        assertThat(w.get("state")).isEqualTo("NEW");
        assertThat(w.get("state_reason")).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM device_otp_challenges WHERE id = ?", String.class,
                askingChallenge)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_security_events WHERE event_type = 'DEVICE_REVOKED' "
                + "AND msisdn = ?", Integer.class, msisdn)).isZero();

        // No phone in use was signed out: the ordinary new-phone notice, not PHONE_REPLACED.
        verify(whatsApp, timeout(5000)).sendCustomNotification(eq(msisdn), contains("A new phone"));
        expectNoPhoneReplacedNotice(msisdn);
    }

    @Test
    @DisplayName("one phone per number: a phone on a handset banned for every account is left as it is, never "
            + "signed out (which would launder the ban)")
    void singleDevice_skipsAHandsetBannedForEveryAccount() throws Exception {
        String mine = number();
        String other = number();
        String handset = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        trustedPhone(mine, handset);
        trustedPhone(other, handset);
        // The fraud desk bans the handset for every account through the console (CONFIRMED_FRAUD on the other
        // number's row).
        banForEveryAccount(publicId(other, handset));

        bind(mine, fresh);

        Map<String, Object> kept = row(mine, handset);
        assertThat(kept.get("state")).isEqualTo("TRUSTED");
        assertThat(kept.get("state_reason")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_security_events WHERE event_type = 'DEVICE_REVOKED' "
                + "AND msisdn = ?", Integer.class, mine)).isZero();
        expectNoPhoneReplacedNotice(mine);

        // Your devices, from the phone now signed in, still shows the handset — blocked.
        String auth = customer(mine);
        String list = mvc.perform(get("/auth/devices").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", fresh))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> shown = JsonPath.read(list, "$.data.devices[?(@.deviceId == '"
                + publicId(mine, handset) + "')]");
        assertThat(shown).hasSize(1);
        assertThat(shown.get(0)).containsEntry("current", false).containsEntry("state", "BANNED")
                .containsEntry("status", "Blocked. Call InnBucks support");
    }

    @Test
    @DisplayName("one phone per number is per number: a handset shared with another number stays signed in there")
    void singleDevice_isPerNumber() throws Exception {
        String mine = number();
        String other = number();
        String handset = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        trustedPhone(mine, handset);
        trustedPhone(other, handset);

        bind(mine, fresh);

        assertReplaced(mine, handset);
        Map<String, Object> theirs = row(other, handset);
        assertThat(theirs.get("state")).isEqualTo("TRUSTED");
        assertThat(theirs.get("state_reason")).isNull();
    }

    @Test
    @DisplayName("one phone per number: the call center sees why a phone was signed out, and that it is asking to "
            + "come back")
    void singleDevice_supportSeesWhyAPhoneWasSignedOut() throws Exception {
        String msisdn = number();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        trustedPhone(msisdn, first);
        bind(msisdn, second);
        String firstId = publicId(msisdn, first);

        String overview = overview(msisdn);
        List<String> guidance = JsonPath.read(overview, "$.data.devices[?(@.deviceId == '" + firstId + "')].agentGuidance");
        assertThat(guidance).singleElement().asString().matches(Pattern.quote("Signed out at ")
                + "\\d{2}:\\d{2}( on \\d{1,2} [A-Z][a-z]{2})?" + Pattern.quote(" when another phone of this number "
                + "signed in (one phone per number). To use this phone again the customer signs in on it with a code "
                + "and then the PIN, which signs the other phone out. If they don't recognise the phone that is signed "
                + "in now, block that one (UNTIL_UNLOCKED)."));
        List<String> revoked = JsonPath.read(overview, "$.data.recentEvents[?(@.type == 'DEVICE_REVOKED')].description");
        assertThat(revoked).containsExactly("Signed out: another phone of this number signed in");

        // The signed-out phone asks for a code to come back.
        assertThat((String) JsonPath.read(clientService(msisdn, first, "RENEW", "{}"), "$.data.decision"))
                .isEqualTo("OTP_REQUIRED");
        List<String> comingBack = JsonPath.read(overview(msisdn), "$.data.devices[?(@.deviceId == '" + firstId
                + "')].agentGuidance");
        assertThat(comingBack).containsExactly("Signed out earlier when another phone of this number signed in, and now "
                + "asking for a code to come back. Coming back signs the other phone out. If the customer isn't "
                + "switching back to this phone, block it (UNTIL_UNLOCKED).");
    }

    @Test
    @DisplayName("one phone per number: the *569# Block menu marks the phone that is signed in, beside the one it "
            + "signed out")
    void singleDevice_ussdBlockMenuMarksTheSignedInPhone() throws Exception {
        String msisdn = number();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        trustedPhone(msisdn, first);
        bind(msisdn, second);
        LocalDateTime firstLastSeen = ((Timestamp) row(msisdn, first).get("last_seen_at")).toLocalDateTime();
        String lastUsed = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
                .format(firstLastSeen.atOffset(ZoneOffset.UTC).atZoneSameInstant(marketTimeZone.zone()));

        String active = ussd("/device-security/ussd/devices/active", "{\"msisdn\":\"" + msisdn + "\"}");
        String menu = JsonPath.read(active, "$.data.menuText");
        // Newest first: the phone signed in now, then the one it signed out.
        assertThat(menu).isEqualTo("Choose the phone to block:\n1. Samsung SM-A155F (signed in)\n"
                + "2. Samsung SM-A155F (last used " + lastUsed + ")");
        assertThat(menu.split(Pattern.quote("(signed in)"), -1)).hasSize(2);
        List<Integer> secondOption = JsonPath.read(active, "$.data.devices[?(@.deviceId == '"
                + publicId(msisdn, second) + "')].option");
        assertThat(secondOption).containsExactly(1);
    }

    @Test
    @DisplayName("a login result for a handset banned on every account after its ticket was issued binds nothing, "
            + "and the ticket is refused at redeem")
    void loginResult_onAHandsetBannedForEveryAccount_bindsNothing() throws Exception {
        String banned = number();
        String asking = number();
        String handset = UUID.randomUUID().toString();
        trustedPhone(banned, handset);
        String challenge = JsonPath.read(clientService(asking, handset, "SIGN_IN", "{}"), "$.data.challengeId");
        String token = verifyOtp(challenge, sendAndCapture(challenge, "WHATSAPP", handset, asking), handset);
        String ticket = JsonPath.read(token, "$.data.loginTicket");
        assertThat(state(asking, handset)).isEqualTo("PENDING_PIN");

        // Banned for every account: the ban cuts the ticket off...
        banForEveryAccount(publicId(banned, handset));
        assertThat(jdbc.queryForObject("SELECT voided_at IS NOT NULL FROM device_login_tickets WHERE jti = ?",
                Boolean.class, jti(ticket))).isTrue();

        // ...and one that escaped the cut-off is recorded but never binds the phone.
        jdbc.update("UPDATE device_login_tickets SET voided_at = NULL WHERE jti = ?", jti(ticket));
        loginResult(jti(ticket), "SUCCESS")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("PENDING_PIN"));
        Map<String, Object> pair = row(asking, handset);
        assertThat(pair.get("state")).isEqualTo("PENDING_PIN");
        assertThat(pair.get("trusted_until")).isNull();
        assertThat(pair.get("bound_at")).isNull();

        redeem(ticket, asking, handset, "SIGN_IN")
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.reason").value("INVALID"));
    }

    @Test
    @DisplayName("one phone per number: a step-up code the phone asked for before it was signed out is spent, but mints "
            + "no proof (403 device_not_trusted)")
    void singleDevice_stepUpProofIsRefusedForAReplacedPhone() throws Exception {
        String msisdn = number();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        trustedPhone(msisdn, first);
        String auth = customer(msisdn);

        String asked = stepUp(auth, first, "{\"action\":\"LARGE_TRANSFER\",\"force\":true}");
        assertThat((String) JsonPath.read(asked, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        String challenge = JsonPath.read(asked, "$.data.challengeId");
        mvc.perform(post("/auth/device/challenge/send").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", first).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"WHATSAPP\"}"))
                .andExpect(status().isOk());
        String code = lastCode(msisdn);
        // A step-up code is not a sign-in code: using it signs nothing out, so it says nothing about that.
        assertThat(lastOtpText(msisdn)).isEqualTo("InnBucks code: " + code + ". It expires in 5 minutes. Never share "
                + "it — InnBucks will never ask for it.");

        bind(msisdn, second);
        assertThat(jdbc.queryForObject("SELECT status FROM device_otp_challenges WHERE id = ?", String.class, challenge))
                .isEqualTo("VOID");

        // A code opened just after the sign-out escapes its void: verify re-checks the phone before minting a proof.
        jdbc.update("UPDATE device_otp_challenges SET status = 'OPEN', closed_at = NULL WHERE id = ?", challenge);
        mvc.perform(post("/auth/device/challenge/verify").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", first).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challenge + "\",\"otp\":\"" + code + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("device_not_trusted"))
                .andExpect(jsonPath("$.message").value(NOT_SIGNED_IN));
        // The code is spent all the same, and no step-up was recorded as passed.
        assertThat(jdbc.queryForObject("SELECT status FROM device_otp_challenges WHERE id = ?", String.class, challenge))
                .isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_security_events WHERE msisdn = ? "
                + "AND event_type = 'SESSION_STEP_UP_VERIFIED'", Integer.class, msisdn)).isZero();
    }

    // =====================================================================================
    // Ticket redeem: what it still accepts, and when it judges the phone
    // =====================================================================================

    @Test
    @DisplayName("redeem still accepts a trusted phone's sign-in ticket and a LOOKUP ticket (no phone), and refuses a "
            + "removed phone's un-voided ticket with one phone per number OFF too")
    void redeem_legitTicketsStillRedeem() throws Exception {
        // A trusted phone's ordinary sign-in ticket.
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        String signIn = trustedPhone(msisdn, install);
        assertThat((String) JsonPath.read(signIn, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(signIn, "$.data.trust.state")).isEqualTo("TRUSTED");
        String ticket = JsonPath.read(signIn, "$.data.loginTicket");
        redeem(ticket, msisdn, install, "SIGN_IN")
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.ticketId").value(jti(ticket)))
                .andExpect(jsonPath("$.data.purpose").value("SIGN_IN"));

        // A LOOKUP ticket names no phone, so there is no phone to be signed out of: it redeems.
        String lookupNumber = number();
        String lookupInstall = UUID.randomUUID().toString();
        String lookup = clientService(lookupNumber, lookupInstall, "LOOKUP", "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(lookup, "$.data.decision")).isEqualTo("TOKEN");
        String lookupTicket = JsonPath.read(lookup, "$.data.loginTicket");
        assertThat(jdbc.queryForObject("SELECT customer_device_id IS NULL FROM device_login_tickets WHERE jti = ?",
                Boolean.class, jti(lookupTicket))).isTrue();
        redeem(lookupTicket, lookupNumber, lookupInstall, "LOOKUP")
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.ticketId").value(jti(lookupTicket)))
                .andExpect(jsonPath("$.data.purpose").value("LOOKUP"));

        // With the rule OFF, a phone the customer removed in Your devices: the removal voids its ticket, and one
        // that escaped the void is still refused — the check is about the phone, not the rule.
        properties.getTrust().setSingleDevice(false);
        String owner = number();
        String kept = UUID.randomUUID().toString();
        String removed = UUID.randomUUID().toString();
        trustedPhone(owner, kept);
        String removedTicket = JsonPath.read(trustedPhone(owner, removed), "$.data.loginTicket");
        mvc.perform(delete("/auth/devices/" + publicId(owner, removed)).header("Authorization", customer(owner))
                        .header("x-api-key", BROKER_KEY).header("x-device-id", kept))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(true));
        assertThat(state(owner, removed)).isEqualTo("REVOKED");
        assertThat(ticketVoided(removedTicket)).isTrue();

        jdbc.update("UPDATE device_login_tickets SET voided_at = NULL WHERE jti = ?", jti(removedTicket));
        redeem(removedTicket, owner, removed, "SIGN_IN")
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.reason").value("INVALID"));
        assertThat(ticketVoided(removedTicket)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_security_events WHERE event_type = 'TICKET_REJECTED' "
                + "AND reason = 'INVALID' AND note = ?", Integer.class,
                "ticket " + jti(removedTicket) + ": phone no longer signed in")).isEqualTo(1);
    }

    @Test
    @DisplayName("redeem judges a ticket's PIN grace and trust window as they were when it was minted, but the phone's "
            + "state as it is now")
    void redeem_judgesTheGraceAndTrustWindowAtMintTime() throws Exception {
        // A PIN retry inside the grace: the grace was open when the TOKEN was minted and has closed by the time the
        // PIN arrives. The ticket still redeems, and the login binds the phone — no second code.
        String graceNumber = number();
        String graceInstall = UUID.randomUUID().toString();
        String challenge = JsonPath.read(clientService(graceNumber, graceInstall, "SIGN_IN", "{}"), "$.data.challengeId");
        String first = verifyOtp(challenge, sendAndCapture(challenge, "WHATSAPP", graceInstall, graceNumber), graceInstall);
        loginResult(jti(JsonPath.read(first, "$.data.loginTicket")), "WRONG_PIN")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("PENDING_PIN"));
        String retry = clientService(graceNumber, graceInstall, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(retry, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(retry, "$.message")).isEqualTo(TOKEN_LINE);
        assertThat((String) JsonPath.read(retry, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        String retryTicket = JsonPath.read(retry, "$.data.loginTicket");
        LocalDateTime graceMinted = mintedAMinuteEarlier(retryTicket);
        jdbc.update("UPDATE customer_devices SET pin_grace_until = ? WHERE msisdn = ? AND install_id_hash = ?",
                graceMinted.plusSeconds(1), graceNumber, DeviceIdentity.hashInstallId(graceInstall));
        assertThat(((Timestamp) row(graceNumber, graceInstall).get("pin_grace_until")).toLocalDateTime())
                .isAfter(graceMinted).isBefore(LocalDateTime.now(ZoneOffset.UTC));

        redeem(retryTicket, graceNumber, graceInstall, "SIGN_IN")
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.ticketId").value(jti(retryTicket)));
        loginResult(jti(retryTicket), "SUCCESS")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));
        assertThat(state(graceNumber, graceInstall)).isEqualTo("TRUSTED");

        // A trusted phone's window: open when the TOKEN was minted, run out by the time the PIN arrives.
        String trustNumber = number();
        String trustInstall = UUID.randomUUID().toString();
        String trustTicket = JsonPath.read(trustedPhone(trustNumber, trustInstall), "$.data.loginTicket");
        LocalDateTime trustMinted = mintedAMinuteEarlier(trustTicket);
        jdbc.update("UPDATE customer_devices SET trusted_until = ? WHERE msisdn = ? AND install_id_hash = ?",
                trustMinted.plusSeconds(1), trustNumber, DeviceIdentity.hashInstallId(trustInstall));
        assertThat(((Timestamp) row(trustNumber, trustInstall).get("trusted_until")).toLocalDateTime())
                .isAfter(trustMinted).isBefore(LocalDateTime.now(ZoneOffset.UTC));
        redeem(trustTicket, trustNumber, trustInstall, "SIGN_IN")
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.ticketId").value(jti(trustTicket)));

        // The STATE is read now: a trusted phone moved to STEP_UP after its ticket was minted is refused, its trust
        // window notwithstanding, and the ticket is voided.
        String stepNumber = number();
        String stepInstall = UUID.randomUUID().toString();
        String stepTicket = JsonPath.read(trustedPhone(stepNumber, stepInstall), "$.data.loginTicket");
        jdbc.update("UPDATE customer_devices SET state = 'STEP_UP' WHERE msisdn = ? AND install_id_hash = ?",
                stepNumber, DeviceIdentity.hashInstallId(stepInstall));
        assertThat(((Timestamp) row(stepNumber, stepInstall).get("trusted_until")).toLocalDateTime())
                .isAfter(LocalDateTime.now(ZoneOffset.UTC));
        redeem(stepTicket, stepNumber, stepInstall, "SIGN_IN")
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.reason").value("INVALID"));
        assertThat(ticketVoided(stepTicket)).isTrue();
    }

    // =====================================================================================
    // One phone per number: the edges of the rule, and the rule switched off
    // =====================================================================================

    @Test
    @DisplayName("one phone per number: a phone that is due a code (trust expired, asked on renewal) is still the "
            + "customer's phone — it is signed out too, its open code dies, and every channel hears it")
    void singleDevice_aPhoneDueACodeIsSignedOutToo() throws Exception {
        String msisdn = number();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        trustedPhone(msisdn, a);
        forgetMessagesAfterNewPhoneNotices(msisdn, 1);
        jdbc.update("UPDATE customer_devices SET trusted_until = ? WHERE msisdn = ? AND install_id_hash = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1), msisdn, DeviceIdentity.hashInstallId(a));

        // A's trust ran out: its silent renewal asks for a code, which it has not typed yet.
        String renew = clientService(msisdn, a, "RENEW", "{}");
        assertThat((String) JsonPath.read(renew, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        assertThat((String) JsonPath.read(renew, "$.data.reason")).isEqualTo("TRUST_EXPIRED");
        assertThat((String) JsonPath.read(renew, "$.message")).isEqualTo(OTP_LINE);
        String aChallenge = JsonPath.read(renew, "$.data.challengeId");
        Map<String, Object> due = row(msisdn, a);
        assertThat(due.get("state")).isEqualTo("STEP_UP");
        assertThat(due.get("state_reason")).isEqualTo("TRUST_EXPIRED");
        assertThat(due.get("bound_at")).isNotNull();
        assertThat(challengeStatus(aChallenge)).isEqualTo("OPEN");

        // B proves the SIM: A had been bound, so the PIN screen warns. B's code is B's own and leaves A's open.
        String bChallenge = JsonPath.read(clientService(msisdn, b, "SIGN_IN", "{}"), "$.data.challengeId");
        String token = verifyOtp(bChallenge, sendAndCapture(bChallenge, "WHATSAPP", b, msisdn), b);
        assertThat((String) JsonPath.read(token, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(token, "$.message")).isEqualTo(TOKEN_LINE_SIGNING_OUT);
        assertThat(challengeStatus(aChallenge)).isEqualTo("OPEN");
        assertThat(state(msisdn, a)).isEqualTo("STEP_UP");

        loginResult(jti(JsonPath.read(token, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));

        assertReplaced(msisdn, a);
        assertThat(challengeStatus(aChallenge)).isEqualTo("VOID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_security_events WHERE event_type = 'DEVICE_REVOKED' "
                + "AND reason = 'REPLACED' AND device_id = ?", Integer.class,
                UUID.fromString(publicId(msisdn, a)))).isEqualTo(1);
        expectPhoneReplacedNotices(msisdn, 1);
        verify(sms, after(500).never()).sendSms(eq(msisdn), contains("A new phone"), anyString());
        verify(whatsApp, never()).sendCustomNotification(eq(msisdn), contains("A new phone"));
    }

    @Test
    @DisplayName("one phone per number OFF: the code and the PIN screen say nothing about other phones, and a second "
            + "phone joins the first with the ordinary new-phone notice")
    void singleDevice_offLeavesTheCodeAndTokenLinesAlone() throws Exception {
        properties.getTrust().setSingleDevice(false);
        String msisdn = number();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        trustedPhone(msisdn, a);
        forgetMessagesAfterNewPhoneNotices(msisdn, 1);
        assertThat(row(msisdn, a).get("bound_at")).isNotNull();

        String asked = clientService(msisdn, b, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(asked, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        assertThat((String) JsonPath.read(asked, "$.message")).isEqualTo(OTP_LINE);
        String challenge = JsonPath.read(asked, "$.data.challengeId");
        String code = sendAndCapture(challenge, "WHATSAPP", b, msisdn);
        // The code reads exactly as it did before one phone per number existed.
        assertThat(lastOtpText(msisdn)).isEqualTo("InnBucks code: " + code + ". It expires in 5 minutes. Never share "
                + "it — InnBucks will never ask for it.");

        // A is bound and in use, yet the PIN screen warns of nothing: nothing will be signed out.
        String token = verifyOtp(challenge, code, b);
        assertThat((String) JsonPath.read(token, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(token, "$.message")).isEqualTo(TOKEN_LINE);
        assertThat((String) JsonPath.read(token, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        loginResult(jti(JsonPath.read(token, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));

        assertThat(state(msisdn, a)).isEqualTo("TRUSTED");
        assertThat(state(msisdn, b)).isEqualTo("TRUSTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_security_events WHERE event_type = 'DEVICE_REVOKED' "
                + "AND msisdn = ?", Integer.class, msisdn)).isZero();
        verify(whatsApp, timeout(5000)).sendCustomNotification(eq(msisdn), contains("A new phone"));
        expectNoPhoneReplacedNotice(msisdn);
    }

    @Test
    @DisplayName("one phone per number: setting a PIN on a new phone signs nothing out; the sign-in that follows in "
            + "the grace warns, and its PIN signs the other phone out")
    void singleDevice_pinIssueThenSignInReplaces() throws Exception {
        String msisdn = number();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        trustedPhone(msisdn, a);
        forgetMessagesAfterNewPhoneNotices(msisdn, 1);

        // A PIN issue is a sign-in purpose: its code says what using it does.
        String asked = clientService(msisdn, b, "PIN_ISSUE", "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(asked, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        assertThat((String) JsonPath.read(asked, "$.message")).isEqualTo(OTP_LINE);
        String challenge = JsonPath.read(asked, "$.data.challengeId");
        String code = sendAndCapture(challenge, "WHATSAPP", b, msisdn);
        assertThat(lastOtpText(msisdn)).isEqualTo("InnBucks code: " + code + ". It expires in 5 minutes. Never share "
                + "it — InnBucks will never ask for it. Using it signs InnBucks out on any other phone.")
                .endsWith(" Using it signs InnBucks out on any other phone.");
        String pinIssue = verifyOtp(challenge, code, b);
        assertThat((String) JsonPath.read(pinIssue, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(pinIssue, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        String pinTicket = JsonPath.read(pinIssue, "$.data.loginTicket");
        redeem(pinTicket, msisdn, b, "PIN_ISSUE").andExpect(jsonPath("$.data.valid").value(true));

        // The PIN is set: no bind, nothing signed out, the customer told of the PIN.
        loginResult(jti(pinTicket), "SUCCESS")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("PENDING_PIN"));
        assertThat(state(msisdn, b)).isEqualTo("PENDING_PIN");
        assertThat(state(msisdn, a)).isEqualTo("TRUSTED");
        verify(whatsApp, timeout(5000)).sendCustomNotification(msisdn,
                "Your InnBucks PIN was just set. If this wasn't you, dial *569# now.");
        expectNoPhoneReplacedNotice(msisdn);

        // Signing in with the new PIN, in the grace the PIN issue kept open: no second code, and the warning.
        String signIn = clientService(msisdn, b, "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(signIn, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(signIn, "$.message")).isEqualTo(TOKEN_LINE_SIGNING_OUT);
        assertThat((String) JsonPath.read(signIn, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        loginResult(jti(JsonPath.read(signIn, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));

        assertThat(state(msisdn, b)).isEqualTo("TRUSTED");
        assertReplaced(msisdn, a);
        expectPhoneReplacedNotices(msisdn, 1);
    }

    @Test
    @DisplayName("one phone per number: a handset banned for every account manages nothing — not Your devices, not a "
            + "step-up, and *569# does not mark it signed in — though its row for this number still reads TRUSTED")
    void singleDevice_aHandsetBannedForEveryAccountManagesNothing() throws Exception {
        String mine = number();
        String other = number();
        String handset = UUID.randomUUID().toString();
        trustedPhone(mine, handset);
        trustedPhone(other, handset);
        String mineId = publicId(mine, handset);
        // The fraud desk bans the handset for every account through the console (CONFIRMED_FRAUD on the other
        // number's row). This number's row is cut off but keeps its state.
        banForEveryAccount(publicId(other, handset));
        assertThat(state(mine, handset)).isEqualTo("TRUSTED");

        String auth = customer(mine);
        mvc.perform(get("/auth/devices").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", handset))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("device_not_trusted"))
                .andExpect(jsonPath("$.message").value(NOT_SIGNED_IN));
        mvc.perform(delete("/auth/devices/" + mineId).header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", handset))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("device_not_trusted"));
        mvc.perform(post("/auth/device/challenge").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", handset).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"LARGE_TRANSFER\",\"force\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("device_not_trusted"))
                .andExpect(jsonPath("$.message").value(NOT_SIGNED_IN));

        // *569# does not call it the signed-in phone either: the same one test decides.
        String menu = JsonPath.read(ussd("/device-security/ussd/devices/active", "{\"msisdn\":\"" + mine + "\"}"),
                "$.data.menuText");
        assertThat(menu).startsWith("Choose the phone to block:\n1. Samsung SM-A155F (last used ")
                .doesNotContain("(signed in)");

        // Nothing was opened or changed on the way to the refusals.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_otp_challenges WHERE msisdn = ? "
                + "AND purpose = 'SESSION_STEP_UP'", Integer.class, mine)).isZero();
        assertThat(state(mine, handset)).isEqualTo("TRUSTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_security_events WHERE event_type = 'DEVICE_REVOKED' "
                + "AND msisdn = ?", Integer.class, mine)).isZero();
    }

    @Test
    @DisplayName("one phone per number: a phone in its PIN grace may list the number's phones but not remove one — "
            + "removing needs the PIN as well as the SIM")
    void singleDevice_removingNeedsThePin() throws Exception {
        String msisdn = number();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        trustedPhone(msisdn, a);
        String aId = publicId(msisdn, a);
        String challenge = JsonPath.read(clientService(msisdn, b, "SIGN_IN", "{}"), "$.data.challengeId");
        String token = verifyOtp(challenge, sendAndCapture(challenge, "WHATSAPP", b, msisdn), b);
        assertThat((String) JsonPath.read(token, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        String bId = publicId(msisdn, b);
        String auth = customer(msisdn);

        // Listing is allowed in the grace: B sees itself signing in, and A still signed in.
        String list = mvc.perform(get("/auth/devices").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", b))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> devices = JsonPath.read(list, "$.data.devices");
        assertThat(devices).extracting(d -> d.get("deviceId")).containsExactlyInAnyOrder(aId, bId);
        List<Map<String, Object>> self = JsonPath.read(list, "$.data.devices[?(@.deviceId == '" + bId + "')]");
        assertThat(self.get(0)).containsEntry("current", true).containsEntry("state", "PENDING_PIN")
                .containsEntry("status", "Signing in");
        List<Map<String, Object>> theOther = JsonPath.read(list, "$.data.devices[?(@.deviceId == '" + aId + "')]");
        assertThat(theOther.get(0)).containsEntry("current", false).containsEntry("state", "TRUSTED")
                .containsEntry("status", "Signed in");

        // Removing is not: B has proved the SIM, not yet the PIN.
        mvc.perform(delete("/auth/devices/" + aId).header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", b))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("device_not_trusted"))
                .andExpect(jsonPath("$.message").value(NOT_SIGNED_IN));
        assertThat(state(msisdn, a)).isEqualTo("TRUSTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_security_events WHERE event_type = 'DEVICE_REVOKED' "
                + "AND msisdn = ?", Integer.class, msisdn)).isZero();

        // The PIN binds B and signs A out; B now lists only itself, and there is nothing left of A to remove.
        loginResult(jti(JsonPath.read(token, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));
        assertReplaced(msisdn, a);
        String after = mvc.perform(get("/auth/devices").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", b))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> only = JsonPath.read(after, "$.data.devices");
        assertThat(only).singleElement().satisfies(d -> assertThat(d).containsEntry("deviceId", bId)
                .containsEntry("current", true).containsEntry("state", "TRUSTED").containsEntry("status", "Signed in"));
        mvc.perform(delete("/auth/devices/" + aId).header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", b))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("device_not_found"));
    }

    @Test
    @DisplayName("*569# Block menu with one phone per number OFF reads as it always has: a last-used date, never "
            + "'(signed in)'")
    void ussdActiveMenu_withTheRuleOff_isUnchanged() throws Exception {
        properties.getTrust().setSingleDevice(false);
        String msisdn = number();
        String install = UUID.randomUUID().toString();
        trustedPhone(msisdn, install);
        assertThat(state(msisdn, install)).isEqualTo("TRUSTED");
        LocalDateTime lastSeen = ((Timestamp) row(msisdn, install).get("last_seen_at")).toLocalDateTime();
        String lastUsed = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
                .format(lastSeen.atOffset(ZoneOffset.UTC).atZoneSameInstant(marketTimeZone.zone()));

        String active = ussd("/device-security/ussd/devices/active", "{\"msisdn\":\"" + msisdn + "\"}");
        String menu = JsonPath.read(active, "$.data.menuText");
        assertThat(menu).isEqualTo("Choose the phone to block:\n1. Samsung SM-A155F (last used " + lastUsed + ")");
        assertThat(menu).contains("(last used ").doesNotContain("(signed in)");
        assertThat((String) JsonPath.read(active, "$.data.devices[0].deviceId")).isEqualTo(publicId(msisdn, install));
    }

    @Test
    @DisplayName("support's VELOCITY guidance mentions a customer switching between two phones only while one phone "
            + "per number is on")
    void console_velocityGuidance_mentionsSwitchingOnlyWithTheRule() throws Exception {
        String switching = "or the customer switching between two phones (only one can be signed in at a time)";
        String time = "\\d{2}:\\d{2}( on \\d{1,2} [A-Z][a-z]{2})?";

        String withRule = velocityGuidance(velocityPausedNumber());
        assertThat(withRule).contains(switching).matches(Pattern.quote("Paused until ") + time
                + Pattern.quote(" and lifts on its own after an unusual number of code requests — possibly someone "
                + "else trying this number, " + switching + ". Verify the caller carefully before unlocking."));

        properties.getTrust().setSingleDevice(false);
        String withoutRule = velocityGuidance(velocityPausedNumber());
        assertThat(withoutRule).doesNotContain("switching").matches(Pattern.quote("Paused until ") + time
                + Pattern.quote(" and lifts on its own after an unusual number of code requests — possibly someone "
                + "else trying this number. Verify the caller carefully before unlocking."));
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

    /** A phone taken through a code and the PIN to TRUSTED, without the extra sign-in {@link #trustedPhone} ends on. */
    private void bind(String msisdn, String install) throws Exception {
        String challenge = JsonPath.read(clientService(msisdn, install, "SIGN_IN", "{}"), "$.data.challengeId");
        String token = verifyOtp(challenge, sendAndCapture(challenge, "WHATSAPP", install, msisdn), install);
        loginResult(jti(JsonPath.read(token, "$.data.loginTicket")), "SUCCESS")
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));
    }

    /**
     * Moves a ticket's mint time a minute back — the PIN arrives a minute after the TOKEN — and returns the new mint
     * time as stored (UTC wall clock; read and written as LocalDateTime, so no JVM zone is involved).
     */
    private LocalDateTime mintedAMinuteEarlier(String ticket) {
        LocalDateTime minted = jdbc.queryForObject("SELECT issued_at FROM device_login_tickets WHERE jti = ?",
                LocalDateTime.class, jti(ticket)).minusMinutes(1);
        jdbc.update("UPDATE device_login_tickets SET issued_at = ? WHERE jti = ?", minted, jti(ticket));
        return minted;
    }

    private boolean ticketVoided(String ticket) {
        return jdbc.queryForObject("SELECT voided_at IS NOT NULL FROM device_login_tickets WHERE jti = ?",
                Boolean.class, jti(ticket));
    }

    private String challengeStatus(String challengeId) {
        return jdbc.queryForObject("SELECT status FROM device_otp_challenges WHERE id = ?", String.class, challengeId);
    }

    /** A fresh number paused for VELOCITY the way {@code otpVelocity_blocks} does it: five codes, the sixth refused. */
    private String velocityPausedNumber() throws Exception {
        String msisdn = number();
        for (int i = 0; i < 5; i++) {
            assertThat((String) JsonPath.read(clientService(msisdn, UUID.randomUUID().toString(), "SIGN_IN", "{}"),
                    "$.data.decision")).isEqualTo("OTP_REQUIRED");
        }
        String sixth = clientService(msisdn, UUID.randomUUID().toString(), "SIGN_IN", "{}");
        assertThat((String) JsonPath.read(sixth, "$.data.decision")).isEqualTo("TEMP_BLOCKED");
        assertThat((String) JsonPath.read(sixth, "$.data.reason")).isEqualTo("VELOCITY");
        return msisdn;
    }

    /** What the console tells the agent about the number's one paused phone. */
    private String velocityGuidance(String msisdn) throws Exception {
        String overview = overview(msisdn);
        List<String> paused = JsonPath.read(overview, "$.data.devices[?(@.state == 'TEMP_BLOCKED')].stateReason");
        assertThat(paused).containsExactly("VELOCITY");
        List<String> guidance = JsonPath.read(overview, "$.data.devices[?(@.state == 'TEMP_BLOCKED')].agentGuidance");
        assertThat(guidance).hasSize(1);
        return guidance.get(0);
    }

    /** The (number, phone) row, straight from the table. */
    private Map<String, Object> row(String msisdn, String install) {
        return jdbc.queryForMap("SELECT * FROM customer_devices WHERE msisdn = ? AND install_id_hash = ?",
                msisdn, DeviceIdentity.hashInstallId(install));
    }

    private String state(String msisdn, String install) {
        return (String) row(msisdn, install).get("state");
    }

    private String publicId(String msisdn, String install) {
        return row(msisdn, install).get("public_id").toString();
    }

    /** Signed out by another phone of its number: REVOKED/REPLACED, with nothing left that lets it back in without a code. */
    private void assertReplaced(String msisdn, String install) {
        Map<String, Object> r = row(msisdn, install);
        assertThat(r.get("state")).isEqualTo("REVOKED");
        assertThat(r.get("state_reason")).isEqualTo("REPLACED");
        assertThat(r.get("trusted_until")).isNull();
        assertThat(r.get("pin_grace_until")).isNull();
        assertThat(r.get("otp_verified_at")).isNull();
        assertThat(r.get("bound_at")).isNull();
        assertThat(r.get("cooling_until")).isNull();
    }

    /** The newest code message sent to this number on WhatsApp, in full. */
    private String lastOtpText(String msisdn) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(whatsApp, atLeastOnce()).sendCustomNotification(eq(msisdn), text.capture());
        List<String> all = text.getAllValues();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).startsWith("InnBucks code")) return all.get(i);
        }
        throw new AssertionError("no OTP was sent: " + all);
    }

    /** Lets the "A new phone" notices of the binds so far arrive (async, after commit), then forgets every message. */
    private void forgetMessagesAfterNewPhoneNotices(String msisdn, int binds) {
        verify(whatsApp, timeout(5000).times(binds)).sendCustomNotification(eq(msisdn), contains("A new phone"));
        clearInvocations(whatsApp, sms);
    }

    /** Exactly {@code n} PHONE_REPLACED notices on EACH channel — no more, once the async sends have settled. */
    private void expectPhoneReplacedNotices(String msisdn, int n) {
        verify(sms, timeout(5000).times(n)).sendSms(eq(msisdn), contains(PHONE_REPLACED), anyString());
        verify(whatsApp, timeout(5000).times(n)).sendCustomNotification(eq(msisdn), contains(PHONE_REPLACED));
        verify(sms, after(500).times(n)).sendSms(eq(msisdn), contains(PHONE_REPLACED), anyString());
        verify(whatsApp, times(n)).sendCustomNotification(eq(msisdn), contains(PHONE_REPLACED));
    }

    private void expectNoPhoneReplacedNotice(String msisdn) {
        verify(sms, after(500).never()).sendSms(eq(msisdn), contains(PHONE_REPLACED), anyString());
        verify(whatsApp, after(500).never()).sendCustomNotification(eq(msisdn), contains(PHONE_REPLACED));
    }

    /** POST /auth/device/challenge from this phone, in the customer's fleet session. */
    private String stepUp(String auth, String install, String body) throws Exception {
        return mvc.perform(post("/auth/device/challenge").header("Authorization", auth).header("x-api-key", BROKER_KEY)
                        .header("x-device-id", install).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The call-center overview of a number, as a read-only agent sees it. */
    private String overview(String msisdn) throws Exception {
        return mvc.perform(get("/admin/device-security/customers/" + msisdn.replace("+263", "0"))
                        .with(agent("device-security:read")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The fraud desk's CONFIRMED_FRAUD ban through the console: the handset, for every account on it. */
    private void banForEveryAccount(String deviceId) throws Exception {
        mvc.perform(post("/admin/device-security/devices/" + deviceId + "/ban")
                        .with(agent("device-security:read", "device-security:fraud"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"CONFIRMED_FRAUD\",\"note\":\"FR-2026-0412: mule handset.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.device.deviceWideBan.reason").value("CONFIRMED_FRAUD"));
    }

    /**
     * A console agent holding these permissions, under a fresh name each time: the per-agent lookup limit lives in
     * Redis, so a fixed name would carry its count from one run to the next.
     */
    private static RequestPostProcessor agent(String... permissions) {
        return user("agent-" + UUID.randomUUID() + "@innbucks.co.zw")
                .authorities(Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());
    }

    private String clientService(String msisdn, String install, String context, String integrity) throws Exception {
        return clientService(msisdn, install, "SIGN_IN", context, integrity);
    }

    /** POST /auth/client-service with an explicit purpose (SIGN_IN, PIN_ISSUE or LOOKUP). */
    private String clientService(String msisdn, String install, String purpose, String context, String integrity)
            throws Exception {
        MvcResult r = mvc.perform(broker(post("/auth/client-service"), install)
                        .content(signInBody(msisdn, install, purpose, context, integrity)))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String signInBody(String msisdn, String install, String context, String integrity) {
        return signInBody(msisdn, install, "SIGN_IN", context, integrity);
    }

    private static String signInBody(String msisdn, String install, String purpose, String context, String integrity) {
        return """
                {"requestId":"%s","purpose":"%s","context":"%s","msisdn":"%s",
                 "device":{"installId":"%s","platform":"android","osVersion":"15","model":"SM-A155F","manufacturer":"samsung","appVersion":"2.4.0"},
                 "integrity":%s,
                 "location":{"status":"GRANTED","lat":-17.825,"lng":31.053,"accuracyM":35,"mocked":false}}
                """.formatted(UUID.randomUUID(), purpose, context, msisdn, install,
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
