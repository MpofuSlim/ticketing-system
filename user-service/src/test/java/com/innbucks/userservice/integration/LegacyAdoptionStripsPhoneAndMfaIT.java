package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adopting a legacy staff account locks a squatter out (V44 §2.6.5, S1). The
 * shape: an on-domain account holding a staff role, a sign-in phone and a TOTP —
 * which may be the real person's, or whoever registered the address and had it
 * approved. Adoption (a resend-invite on a profile-less account) creates the
 * profile, which ends every session at once; accepting the invite then moves the
 * phone to the contact number and clears the second factor, so nothing the
 * squatter held — password, TOTP, phone — opens the account again.
 */
class LegacyAdoptionStripsPhoneAndMfaIT extends StaffItSupport {

    private static final String NEW_PASSWORD = "Adopted-Staff-Pass-7q";

    @Test
    void adoptionEndsTheSquattersSessions_andAcceptStripsPhoneAndTotp() throws Exception {
        User legacy = staff("PRODUCT_OFFICER", true);
        String oldPhone = legacy.getPhoneNumber();
        assertThat(oldPhone).isNotNull();

        // 1. The squatter holds a working session (password + TOTP).
        JsonNode squatter = signIn(legacy.getEmail());
        String access = squatter.at("/token").asText();
        String refresh = squatter.at("/refreshToken").asText();
        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        // The directory lists it as a legacy row that can be adopted.
        mockMvc.perform(get("/admin/staff/{id}", legacy.getId()).with(authentication(staffAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.adoptable").value(true));

        // 2. Adoption: INVITED at once, and every session the account held is dead.
        mockMvc.perform(post("/admin/staff/{id}/resend-invite", legacy.getId()).with(authentication(staffAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Adopting a pre-V44 account (OPS-1204).\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVITED"));
        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        // A 401 telling them to use the invite — not a "reuse detected" theft alarm.
        mockMvc.perform(post("/auth/refresh").header("Authorization", "Bearer " + refresh)
                        .header("X-Device-Id", DEVICE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("staff_invite_pending"));
        // The squatter's password still matches, and is refused before any mfaToken.
        passwordStep(legacy.getEmail())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("staff_invite_pending"));

        // 3. The mailbox owner accepts: no sign-in phone, no TOTP.
        accept(inviteTokenSentTo(legacy.getEmail()), NEW_PASSWORD).andExpect(status().isOk());
        User after = users.findById(legacy.getId()).orElseThrow();
        assertThat(after.getPhoneNumber()).isNull();
        assertThat(after.isMfaEnabled()).isFalse();
        assertThat(after.getMfaSecret()).isNull();
        assertThat(single("SELECT contact_phone FROM staff_profiles WHERE user_id = ?1", legacy.getId()))
                .isEqualTo(oldPhone);
        assertThat(single("SELECT adopted FROM staff_profiles WHERE user_id = ?1", legacy.getId())).isEqualTo(true);

        // 4. The old phone reaches nothing: forgot-password by it sends and stores nothing.
        mockMvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phoneNumber", oldPhone))))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM otps WHERE phone_number = ?1", oldPhone)).isZero();

        // The owner's first sign-in enrols a NEW second factor; the old TOTP is gone.
        JsonNode session = signInEnrolling(legacy.getEmail(), NEW_PASSWORD);
        assertThat(strings(claims(session.at("/token").asText()).get("roles"))).containsExactly("PRODUCT_OFFICER");
        assertThat(users.findById(legacy.getId()).orElseThrow().getMfaSecret()).isNotEqualTo(TOTP_SECRET);
    }

    @Test
    void anAccountThatStillRunsABusinessCannotBeAdopted() throws Exception {
        User mixed = staff("PRODUCT_OFFICER", true);
        mixed.getRoles().add("MERCHANT_ADMIN");
        users.save(mixed);
        mockMvc.perform(post("/admin/staff/{id}/resend-invite", mixed.getId()).with(authentication(staffAdmin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("adoption_blocked"))
                .andExpect(jsonPath("$.data.reason").value("holds_non_staff_roles"));
        // Nothing changed: the account is still a legacy row and still signs in.
        assertThat(count("SELECT count(*) FROM staff_profiles WHERE user_id = ?1", mixed.getId())).isZero();
        signIn(mixed.getEmail());
    }
}
