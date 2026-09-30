package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.StaffInvite;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The create and resend responses (V44) never carry a password, the invite
 * token, its hash or the link — the token exists only in the email.
 */
class StaffCreateResponseHasNoSecretsTest {

    @Test
    @DisplayName("create and resend: no password, token, hash or link in the body")
    void noSecrets() throws Exception {
        StaffDispatchHarness h = new StaffDispatchHarness();
        String body = h.mvc.perform(post("/admin/staff").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Tariro","lastName":"Moyo","email":"tariro.moyo@innbucks.co.zw",
                                 "country":"Zimbabwe","roles":["CALL_CENTER_AGENT"],"note":"HR-2291"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertNoSecrets(h, body);

        User created = h.userRows.values().stream()
                .filter(u -> "tariro.moyo@innbucks.co.zw".equals(u.getEmail())).findFirst().orElseThrow();
        String resent = h.mvc.perform(post("/admin/staff/{id}/resend-invite", created.getId()).principal(as(OWNER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNoSecrets(h, resent);
    }

    private static void assertNoSecrets(StaffDispatchHarness h, String body) {
        String token = h.lastRawToken();
        assertThat(body).doesNotContain(token);
        assertThat(body).doesNotContain(token.substring(4));
        for (StaffInvite invite : h.inviteRows.values()) {
            assertThat(body).doesNotContain(invite.getTokenHash());
        }
        assertThat(body).doesNotContain("#token=").doesNotContain("set-password")
                .doesNotContain("\"password\"").doesNotContain("!INVITE-").doesNotContain("tokenHash");
    }
}
