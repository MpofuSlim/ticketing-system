package com.innbucks.userservice.integration;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.SupportItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-agent lookup limit through the real stack (D11), with a small limit so
 * the test reaches it: searches and the device-security reads share one window,
 * the refusal is a 429 with {@code Retry-After}, and agents are counted apart.
 * CI has no Redis here, so this also proves the limiter holds when Redis is down
 * (the per-replica window decides — never fail-open).
 */
@TestPropertySource(properties = {
        "support.limiter.short-window-max=3",
        "support.limiter.daily-max=50"
})
class SupportRateLimitIT extends SupportItSupport {

    @Test
    @DisplayName("the 4th call in the window — searches and device-security reads together — is 429 lookup_rate_limited")
    void limited() throws Exception {
        User merchant = lockedMerchant();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        String colleague = session(eligibleStaff("CALL_CENTER_AGENT", true));

        search(agent, merchant.getPhoneNumber()).andExpect(status().isOk());
        search(agent, "4111111111111111").andExpect(status().isBadRequest());   // refusals count too
        mockMvc.perform(get("/admin/device-security/customers/{m}", merchant.getPhoneNumber())
                        .header("Authorization", agent))
                .andExpect(status().isOk());

        search(agent, merchant.getEmail())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.data.errorCode").value("lookup_rate_limited"))
                .andExpect(jsonPath("$.data.window").value("10m"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").isNumber());
        mockMvc.perform(get("/admin/device-security/customers/{m}", merchant.getPhoneNumber())
                        .header("Authorization", agent))
                .andExpect(status().isTooManyRequests());

        // Another agent has their own window.
        search(colleague, merchant.getPhoneNumber()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("T2: detail reads and writes count too — search + detail + write fill the window; the next write is 429 and changes nothing")
    void detailReadsAndWritesCount() throws Exception {
        User merchant = lockedMerchant();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));

        String lookup = lookupId(agent, merchant.getPhoneNumber());                         // 1: search
        detail(agent, merchant.getId(), lookup).andExpect(status().isOk());                  // 2: detail read
        write(agent, merchant.getId(), "send-password-reset", lookup,
                java.util.UUID.randomUUID().toString()).andExpect(status().isOk());          // 3: write

        write(agent, merchant.getId(), "unlock", lookup, java.util.UUID.randomUUID().toString())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.data.errorCode").value("lookup_rate_limited"));
        User after = users.findById(merchant.getId()).orElseThrow();
        assertThat(after.getLockedUntil()).as("the refused unlock did nothing").isNotNull();
        assertThat(after.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM support_actions WHERE target = ?1 AND op = 'CONSOLE_UNLOCK'",
                String.valueOf(merchant.getId()))).isZero();
        detail(agent, merchant.getId(), lookup).andExpect(status().isTooManyRequests());
    }
}
