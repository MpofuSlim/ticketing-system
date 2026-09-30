package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.testsupport.StaffItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole life of a new staff account, end to end over real Postgres (V44):
 * {@code POST /admin/staff} → the invite email (after commit) → sign-in refused
 * while INVITED → inspect → accept → the first password login is forced into 2FA
 * enrolment → the enrolled session carries exactly the staff role's authority
 * and no organization → the directory shows ACTIVE with the sign-in stamped.
 */
class StaffFirstSignInIT extends StaffItSupport {

    private static final String NEW_PASSWORD = "Tariro-Staff-Pass-7q";

    @Test
    void createInviteAcceptEnrolSignIn() throws Exception {
        String address = staffAddress();
        long id = createStaff(address, null, "CALL_CENTER_AGENT");

        // The account exists with no usable password and no sign-in phone.
        assertThat(users.findById(id).orElseThrow().getPhoneNumber()).isNull();
        String token = inviteTokenSentTo(address);
        assertThat(token).startsWith("STI-");
        // The mailer recorded the outcome on the invite row.
        awaitDelivery(id, "SENT");

        // INVITED: there is no password to sign in with yet — whatever is typed is
        // the ordinary wrong-password answer (no oracle for "this is staff").
        mockMvc.perform(post("/auth/login").header("X-Device-Id", DEVICE).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", address,
                                "password", NEW_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.mfaToken").doesNotExist());

        // Inspect names the person without using the link up.
        mockMvc.perform(post("/auth/staff-invite/inspect").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", token))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.email").value(address))
                .andExpect(jsonPath("$.data.firstName").value("Tariro"));

        accept(token, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(address))
                .andExpect(jsonPath("$.data.token").doesNotExist());
        accept(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("invite_invalid"));

        // First sign-in: forced enrolment, then a session with the staff role's
        // authority exactly — and never an organization.
        JsonNode session = signInEnrolling(address, NEW_PASSWORD);
        JsonNode claims = claims(session.at("/token").asText());
        assertThat(strings(claims.get("roles"))).containsExactly("CALL_CENTER_AGENT");
        assertThat(strings(claims.get("perms")))
                .containsExactlyInAnyOrder("device-security:read", "device-security:manage",
                        "support-console:read", "support-console:manage");
        assertThat(claims.has("orgId")).isFalse();
        assertThat(session.at("/organizationId").isMissingNode() || session.at("/organizationId").isNull()).isTrue();

        // The session works.
        mockMvc.perform(get("/notifications/unread-count")
                        .header("Authorization", "Bearer " + session.at("/token").asText()))
                .andExpect(status().isOk());

        // The directory: ACTIVE, email proven, enrolled, signed in, no invite pending.
        mockMvc.perform(get("/admin/staff/{id}", id).with(authentication(staffAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.mfaEnrolled").value(true))
                .andExpect(jsonPath("$.data.lastSignInAt").isNotEmpty())
                .andExpect(jsonPath("$.data.createdBy.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.data.invite").doesNotExist());
        assertThat(single("SELECT last_sign_in_at FROM users WHERE id = ?1", id)).isNotNull();

        // The history reads as what happened.
        mockMvc.perform(get("/admin/staff/{id}/audit", id).with(authentication(staffAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].type").value("STAFF_INVITE_REPLAYED"))
                .andExpect(jsonPath("$.data.content[0].outcome").value("FAILURE"))
                .andExpect(jsonPath("$.data.content[1].type").value("STAFF_INVITE_ACCEPTED"))
                .andExpect(jsonPath("$.data.content[2].type").value("STAFF_INVITED"))
                .andExpect(jsonPath("$.data.content[2].note")
                        .value("Joins the Harare call-center team on 1 Oct (HR-2291)."));
    }

    private void awaitDelivery(long userId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Object status = single("SELECT delivery_status FROM staff_invites WHERE user_id = ?1 "
                    + "ORDER BY id DESC LIMIT 1", userId);
            if (expected.equals(status)) return;
            Thread.sleep(50);
        }
        assertThat(single("SELECT delivery_status FROM staff_invites WHERE user_id = ?1 ORDER BY id DESC LIMIT 1",
                userId)).isEqualTo(expected);
    }
}
