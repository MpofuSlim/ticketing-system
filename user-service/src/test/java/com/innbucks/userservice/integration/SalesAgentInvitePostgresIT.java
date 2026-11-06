package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sales-agent portal invites (V56) end to end against real Postgres and the
 * real security chain: V56 applies and the entity validates against it, the
 * internal mint is token-gated, the public calls need no session, the code goes
 * to the invite's phone, accept binds the email and password to the account on
 * that phone (created when there is none) with CUSTOMER + SALES_AGENT, the
 * first portal sign-in is forced into 2FA enrolment, and a used link stays used.
 */
class SalesAgentInvitePostgresIT extends StaffItSupport {

    private static final String INTERNAL_TOKEN = "it-internal-token-it-internal-token";
    private static final String NEW_PASSWORD = "Agent-Sells-7q-Pass";

    @MockitoBean SmsNotificationClient sms;
    @MockitoBean WhatsAppNotificationClient whatsApp;

    @BeforeEach
    void resetChannels() {
        reset(sms, whatsApp);
    }

    private ResultActions mint(String phone, String email) throws Exception {
        return mockMvc.perform(post("/users/internal/sales-agent-invites").header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "teamMemberId", UUID.randomUUID().toString(),
                        "organizerUuid", UUID.randomUUID().toString(),
                        "organizerName", "Harare Arena Events",
                        "phoneNumber", phone,
                        "email", email))));
    }

    private ResultActions call(String path, Map<String, String> body) throws Exception {
        return mockMvc.perform(post("/auth/sales-agent-invite/" + path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** Asks for the code and reads it off the SMS the gateway was handed. */
    private String phoneCode(String token, String phone) throws Exception {
        call("phone-code", Map.of("token", token)).andExpect(status().isOk());
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sms, atLeastOnce()).sendSms(eq(phone), message.capture(), anyString());
        List<String> sent = message.getAllValues();
        return sent.get(sent.size() - 1).replaceAll(".*sign-in is (\\d{6}).*", "$1");
    }

    private static String email() {
        return "agent." + unique() + "@gmail.com";
    }

    @Test
    @DisplayName("no account on the phone: one is created, CUSTOMER + SALES_AGENT, and its first sign-in forces 2FA")
    void newAccount_endToEnd() throws Exception {
        String phone = phone();
        String email = email();
        mint(phone, email).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("202 ACCEPTED"))
                .andExpect(jsonPath("$.data.expiresAt").exists());
        String token = inviteTokenSentTo(email);
        assertThat(token).startsWith("SAI-");

        call("inspect", Map.of("token", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.organizerName").value("Harare Arena Events"))
                .andExpect(jsonPath("$.data.maskedPhone").value("****" + phone.substring(phone.length() - 4)));

        String code = phoneCode(token, phone);
        call("accept", Map.of("token", token, "phoneCode", code, "password", NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.next").value("SIGN_IN"));

        User agent = users.findByPhoneNumber(phone).orElseThrow();
        assertThat(agent.getEmail()).isEqualTo(email);
        assertThat(agent.getRoles()).containsExactlyInAnyOrder("CUSTOMER", "SALES_AGENT");
        assertThat(passwordEncoder.matches(NEW_PASSWORD, agent.getPassword())).isTrue();
        CustomerProfile profile = customerProfiles.findByUserId(agent.getId()).orElseThrow();
        assertThat(profile.isPhoneVerified()).isTrue();
        assertThat(count("SELECT count(*) FROM sales_agent_invites WHERE email = ?1 AND used_at IS NOT NULL "
                + "AND account_user_uuid = ?2", email, agent.getUserUuid())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'SALES_AGENT_PORTAL_ACCESS_GRANTED' "
                + "AND target_id = ?1", String.valueOf(agent.getUserUuid()))).isEqualTo(1);

        // The first portal sign-in is a system user's: enrolment is forced.
        mockMvc.perform(post("/auth/login").header("X-Device-Id", DEVICE).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", email, "password", NEW_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfaEnrollmentRequired").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist());

        // A used link stays used.
        call("accept", Map.of("token", token, "phoneCode", code, "password", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("invite_invalid"));
        call("inspect", Map.of("token", token)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("invite_invalid"));
    }

    @Test
    @DisplayName("an InnBucks app customer: the SAME account gets the email, the password and SALES_AGENT")
    void appCustomer_isTheSameAccount() throws Exception {
        String phone = phone();
        User app = users.save(User.builder().firstName("Customer").lastName("Pending").phoneNumber(phone)
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .roles(User.roleNames(User.Role.CUSTOMER)).active(true).approved(true).build());
        customerProfiles.save(CustomerProfile.builder().user(app).registrationTier(1).phoneVerified(true).build());
        String email = email();

        mint(phone, email).andExpect(status().isAccepted());
        String token = inviteTokenSentTo(email);
        String code = phoneCode(token, phone);

        // A wrong code is refused and does not burn the link.
        call("accept", Map.of("token", token, "phoneCode", code.equals("000000") ? "111111" : "000000",
                        "password", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("phone_code_invalid"));

        call("accept", Map.of("token", token, "phoneCode", code, "password", NEW_PASSWORD))
                .andExpect(status().isOk());

        User after = users.findById(app.getId()).orElseThrow();
        assertThat(after.getUserUuid()).isEqualTo(app.getUserUuid());
        assertThat(after.getEmail()).isEqualTo(email);
        assertThat(after.getRoles()).containsExactlyInAnyOrder("CUSTOMER", "SALES_AGENT");
        assertThat(passwordEncoder.matches(NEW_PASSWORD, after.getPassword())).isTrue();
        assertThat(count("SELECT count(*) FROM users WHERE phone_number = ?1", phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("an account with another email is email_mismatch, and nothing changes")
    void otherEmail_isRefused() throws Exception {
        User customer = customer();
        String original = customer.getEmail();
        String invited = email();
        mint(customer.getPhoneNumber(), invited).andExpect(status().isAccepted());
        String token = inviteTokenSentTo(invited);

        call("phone-code", Map.of("token", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("email_mismatch"));
        verify(sms, org.mockito.Mockito.never()).sendSms(anyString(), anyString(), anyString());
        assertThat(users.findById(customer.getId()).orElseThrow().getEmail()).isEqualTo(original);
    }

    @Test
    @DisplayName("internal: 401 without the token through the real security chain; a staff address is 400")
    void internal_isTokenGated() throws Exception {
        mockMvc.perform(post("/users/internal/sales-agent-invites").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/users/internal/sales-agent-invites/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mint(phone(), "tariro.moyo@innbucks.co.zw")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("email_domain_reserved"));
        mockMvc.perform(delete("/users/internal/sales-agent-invites/{id}", UUID.randomUUID())
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(0));
    }

    @Test
    @DisplayName("a newer invite revokes the earlier one; revoke ends the newer one")
    void newerInvite_revokesTheOlder() throws Exception {
        String phone = phone();
        String email = email();
        String teamMember = UUID.randomUUID().toString();
        Map<String, String> body = Map.of("teamMemberId", teamMember, "organizerUuid", UUID.randomUUID().toString(),
                "phoneNumber", phone, "email", email);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/users/internal/sales-agent-invites").header("X-Internal-Token", INTERNAL_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isAccepted());
        }
        // The emails go after commit, off the request thread: wait for both.
        nthInviteTokenSentTo(email, 2);
        List<String> tokens = inviteTokensSentTo(email);
        assertThat(tokens).hasSize(2);
        // The two emails can arrive in either order; exactly one link still works.
        List<Integer> statuses = new java.util.ArrayList<>();
        for (String t : tokens) {
            statuses.add(call("inspect", Map.of("token", t)).andReturn().getResponse().getStatus());
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        String live = tokens.get(statuses.indexOf(200));

        JsonNode revoked = data(mockMvc.perform(delete("/users/internal/sales-agent-invites/{id}", teamMember)
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk()));
        assertThat(revoked.at("/revoked").asInt()).isEqualTo(1);
        call("inspect", Map.of("token", live)).andExpect(status().isBadRequest());
    }
}
