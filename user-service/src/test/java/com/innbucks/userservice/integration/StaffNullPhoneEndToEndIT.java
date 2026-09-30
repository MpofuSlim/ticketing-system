package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.testsupport.StaffItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V44 made {@code users.phone_number} nullable: a staff account has no sign-in
 * phone. This walks every surface that reads the phone for such an account —
 * sign-in and token mint, refresh, the admin views, the S2S contact lookup and
 * notify, deactivation (and its notice) and reactivation, and a password reset —
 * and requires each to answer normally rather than NPE into a 500.
 */
class StaffNullPhoneEndToEndIT extends StaffItSupport {

    private static final String INTERNAL_TOKEN = "it-internal-token-it-internal-token";
    private static final String NEW_PASSWORD = "NullPhone-Staff-Pass-7q";

    @Test
    void aStaffAccountWithNoPhoneWorksEverywhere() throws Exception {
        String address = staffAddress();
        long id = createStaff(address, null, "FRAUD_DESK");
        UUID uuid = users.findById(id).orElseThrow().getUserUuid();
        accept(inviteTokenSentTo(address), NEW_PASSWORD).andExpect(status().isOk());
        assertThat(single("SELECT phone_number FROM users WHERE id = ?1", id)).isNull();

        // Mint + use + refresh.
        JsonNode session = signInEnrolling(address, NEW_PASSWORD);
        assertThat(claims(session.at("/token").asText()).path("phoneNumber").isMissingNode()
                || claims(session.at("/token").asText()).path("phoneNumber").isNull()).isTrue();
        mockMvc.perform(get("/notifications/unread-count")
                        .header("Authorization", "Bearer " + session.at("/token").asText()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/refresh").header("Authorization", "Bearer " + session.at("/refreshToken").asText())
                        .header("X-Device-Id", DEVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        // Admin views.
        mockMvc.perform(get("/admin/staff/{id}", id).with(authentication(staffAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneNumber").doesNotExist());
        mockMvc.perform(get("/admin/staff").param("q", address.substring(0, 10)).with(authentication(staffAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].email").value(address));
        mockMvc.perform(get("/admin/users").with(authentication(staffAdmin())))
                .andExpect(status().isOk());

        // S2S: contact lookup and notify.
        mockMvc.perform(get("/users/internal/{uuid}/contact", uuid).header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(address));
        mockMvc.perform(post("/users/internal/{uuid}/notify", uuid).header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("subject", "Heads up",
                                "message", "A fraud case was assigned to you."))))
                .andExpect(status().isAccepted());

        // Deactivate (sends the security notice) and reactivate.
        mockMvc.perform(post("/admin/staff/{id}/deactivate", id).with(authentication(staffAdmin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Left on 30 Sept.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEACTIVATED"));
        mockMvc.perform(post("/admin/staff/{id}/reactivate", id).with(authentication(staffAdmin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Rehired on 1 Oct.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVITED"));

        // A reset by email for an INVITED account is a silent no-op, not a failure.
        mockMvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", address))))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM otps WHERE phone_number = ?1", address)).isZero();

        // And a second invite brings it back, still with no phone.
        mockMvc.perform(post("/admin/staff/{id}/resend-invite", id).with(authentication(staffAdmin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        accept(nthInviteTokenSentTo(address, 2), NEW_PASSWORD + "x").andExpect(status().isOk());
        assertThat(single("SELECT phone_number FROM users WHERE id = ?1", id)).isNull();
    }
}
