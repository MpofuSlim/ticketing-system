package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.SupportItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /admin/support/customers/search} end to end (V45/V46): a real
 * call-center session, real Postgres, and the access log it writes.
 */
class SupportSearchIT extends SupportItSupport {

    @org.springframework.beans.factory.annotation.Autowired
    com.innbucks.userservice.support.SupportProperties supportProperties;

    @Test
    @DisplayName("an agent finds a merchant by phone: both sections, the console account, and a recorded lookup")
    void searchByPhone() throws Exception {
        User merchant = lockedMerchant();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));

        JsonNode data = data(search(agent, merchant.getPhoneNumber().replace("+263", "0"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("Customer found")));

        assertThat(data.at("/lookupId").asText()).matches("^SLK-[0-9A-Z]{6}$");
        assertThat(data.at("/query/kind").asText()).isEqualTo("PHONE");
        assertThat(data.at("/query/normalised").asText()).isEqualTo(merchant.getPhoneNumber());
        assertThat(data.at("/customer/phone").asText()).isEqualTo(merchant.getPhoneNumber());
        assertThat(data.at("/customer/email").asText()).isEqualTo(merchant.getEmail());
        assertThat(data.at("/customer/name").asText()).isEqualTo("Tariro Moyo");
        assertThat(data.at("/notShown").size()).isZero();
        assertThat(data.at("/staffAccount").asBoolean()).isFalse();
        assertThat(data.at("/focus").isNull()).isTrue();

        JsonNode account = firstConsoleAccount(data);
        assertThat(account.at("/userId").asLong()).isEqualTo(merchant.getId());
        assertThat(account.at("/status").asText()).isEqualTo("ACTIVE");
        assertThat(account.at("/mfaEnrolled").asBoolean()).isTrue();
        assertThat(account.at("/failedSignInAttempts").asInt()).isEqualTo(5);
        // /admin is human-facing: the market offset, not Z.
        assertThat(account.at("/lockedUntil").asText()).endsWith("+02:00");
        assertThat(account.at("/organizations/0/role").asText()).isEqualTo("OWNER");
        assertThat(account.at("/serviceRequests/0/status").asText()).isEqualTo("PENDING");
        assertThat(account.at("/serviceRequests/0/guidance").asText()).startsWith("Waiting for an InnBucks administrator");
        assertThat(account.at("/agentGuidance").asText()).startsWith("Locked after too many wrong passwords until ");
        assertThat(strings(account.at("/actions"))).containsExactly("unlock", "send-password-reset");
        // A console merchant who has never used the app: the section is there, and says so.
        assertThat(data.at("/sections/innbucksApp/status").asText()).isEqualTo("NOT_FOUND");
        assertThat(data.at("/sections/innbucksApp/data/phones/0/msisdn").asText()).isEqualTo(merchant.getPhoneNumber());

        // No secret is anywhere in the response.
        String body = data.toString();
        assertThat(body).doesNotContain(TOTP_SECRET).doesNotContain("mfaSecret").doesNotContain("password\"")
                .doesNotContain("tokenVersion");

        // The lookup is recorded: masked query, resolved keys in full, the sections returned.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT op, outcome, query_kind, query_masked, customer_keys, "
                        + "sections, client_ip_untrusted FROM support_access_log WHERE lookup_id = ?1")
                .setParameter(1, data.at("/lookupId").asText()).getResultList();
        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        assertThat(row[0]).isEqualTo("SEARCH");
        assertThat(row[1]).isEqualTo("OK");
        assertThat(row[2]).isEqualTo("PHONE");
        assertThat((String) row[3]).isEqualTo("****" + merchant.getPhoneNumber().substring(merchant.getPhoneNumber().length() - 4));
        assertThat((String) row[4]).contains(merchant.getPhoneNumber()).contains(merchant.getEmail())
                .contains(merchant.getUserUuid().toString());
        assertThat(row[5]).isEqualTo("console,innbucksApp");
        assertThat(row[6]).isEqualTo("41.221.147.12");
    }

    @Test
    @DisplayName("a super-app customer: the app section with their registration facts; no console account")
    void superAppCustomer() throws Exception {
        User customer = customer();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        JsonNode data = data(search(agent, customer.getPhoneNumber()).andExpect(status().isOk()));
        assertThat(data.at("/sections/console/status").asText()).isEqualTo("NOT_FOUND");
        assertThat(data.at("/sections/console/data/accounts").size()).isZero();
        assertThat(data.at("/sections/innbucksApp/status").asText()).isEqualTo("OK");
        JsonNode profile = data.at("/sections/innbucksApp/data/phones/0/customerProfile");
        assertThat(profile.at("/registrationTier").asInt()).isEqualTo(2);
        assertThat(profile.at("/phoneVerified").asBoolean()).isTrue();
        assertThat(data.at("/customer/name").asText()).isEqualTo("Tendai Dube");
        // A console write can't be aimed at them: they are not a console target of this lookup.
        write(agent, customer.getId(), "unlock", data.at("/lookupId").asText(), java.util.UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_found"));
    }

    @Test
    @DisplayName("a card number is refused 400 query_not_accepted and logged by KIND — its digits are stored nowhere")
    void cardNumberRefused() throws Exception {
        User agentAccount = eligibleStaff("CALL_CENTER_AGENT", true);
        String agent = session(agentAccount);
        java.time.LocalDateTime before = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(5);
        String card = "4111 1111 1111 " + (1000 + (int) (Math.random() * 8999));
        search(agent, card)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("query_not_accepted"));
        String digits = card.replace(" ", "");
        // THIS agent's row, from THIS test — not any refusal another IT happened to leave behind.
        assertThat(count("SELECT count(*) FROM support_access_log WHERE op = 'SEARCH_REFUSED' "
                + "AND outcome = 'query_not_accepted' AND query_kind = 'NOT_ACCEPTED' AND query_masked IS NULL "
                + "AND customer_keys IS NULL AND agent_user_uuid = CAST(?1 AS uuid) AND created_at >= ?2",
                agentAccount.getUserUuid().toString(), before)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM support_access_log WHERE coalesce(query_masked,'') LIKE ?1 "
                + "OR coalesce(customer_keys,'') LIKE ?1", "%" + digits.substring(8) + "%")).isZero();
    }

    @Test
    @DisplayName("a marketplace order ref is 400 query_not_supported; a SEC ref returns only the app section + focus")
    void references() throws Exception {
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        search(agent, "MKT-0A1B2C3D4E5F")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("query_not_supported"))
                .andExpect(jsonPath("$.message").value("Searching by this kind of reference isn't available yet."));

        JsonNode ref = data(search(agent, "sec-8f2kq9").andExpect(status().isOk()));
        assertThat(ref.at("/sections").size()).isEqualTo(1);
        assertThat(ref.at("/sections/innbucksApp/status").asText()).isEqualTo("NOT_FOUND");
        assertThat(ref.at("/sections/console").isMissingNode()).isTrue();
        assertThat(ref.at("/focus/reference").asText()).isEqualTo("SEC-8F2KQ9");
        assertThat(ref.at("/focus/note").asText())
                .isEqualTo("To see this customer's other products, search by their phone or email.");
    }

    @Test
    @DisplayName("S1: looking up a colleague sets staffAccount and shows a STUB — nothing about the account, and no target")
    void staffLookupIsFlagged() throws Exception {
        User colleague = eligibleStaff("CALL_CENTER_SUPERVISOR", true);
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        JsonNode data = data(search(agent, colleague.getEmail()).andExpect(status().isOk()));
        assertThat(data.at("/staffAccount").asBoolean()).isTrue();

        JsonNode stub = firstConsoleAccount(data);
        assertThat(stub.at("/staffAccount").asBoolean()).isTrue();
        assertThat(stub.at("/agentGuidance").asText()).contains("ask a SUPER_ADMIN");
        assertThat(strings(stub.at("/actions"))).isEmpty();
        java.util.List<String> keys = new java.util.ArrayList<>();
        stub.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("staffAccount", "agentGuidance", "actions");
        // Nothing about the colleague anywhere in the console section.
        String console = data.at("/sections/console").toString();
        assertThat(console).doesNotContain("\"userId\"")
                .doesNotContain(colleague.getUserUuid().toString())
                .doesNotContain("CALL_CENTER_SUPERVISOR")
                .doesNotContain("mfaEnrolled").doesNotContain("lockedUntil").doesNotContain("lastSignInAt");
        assertThat(data.at("/customer/name").isNull()).isTrue();

        // Alerted as before, and the colleague's id is not a target of this lookup …
        String lookupId = data.at("/lookupId").asText();
        assertThat(count("SELECT count(*) FROM support_access_log WHERE lookup_id = ?1 AND staff_account = TRUE",
                lookupId)).isEqualTo(1);
        assertThat(com.innbucks.userservice.support.SupportAccessLogWriter.targets((String) single(
                "SELECT section_targets FROM support_access_log WHERE lookup_id = ?1", lookupId)).get("console"))
                .isEmpty();
        // … so a detail read of it is simply not found.
        detail(agent, colleague.getId(), lookupId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_found"));
    }

    @Test
    @DisplayName("T7: an agent with only device-security:read learns nothing about console accounts — no staff flag, no warnings")
    void deviceOnlyAgentLearnsNothingAboutConsoleAccounts() throws Exception {
        User colleague = eligibleStaff("CALL_CENTER_SUPERVISOR", true);
        User fraudDesk = eligibleStaff("FRAUD_DESK", true);
        String agent = session(fraudDesk);
        JsonNode data = data(search(agent, colleague.getEmail()).andExpect(status().isOk()));
        assertThat(data.at("/staffAccount").asBoolean()).isFalse();
        assertThat(data.at("/identityWarnings").size()).isZero();
        assertThat(data.at("/sections/console").isMissingNode()).isTrue();
        assertThat(strings(data.at("/notShown"))).containsExactly("console");
        assertThat(data.toString()).doesNotContain(colleague.getUserUuid().toString());
        // The alert still fires: the log row records the staff match.
        assertThat(count("SELECT count(*) FROM support_access_log WHERE lookup_id = ?1 AND staff_account = TRUE",
                data.at("/lookupId").asText())).isEqualTo(1);
    }

    @Test
    @DisplayName("T5: a caller with neither section read is refused by the SECURITY CHAIN — no log row, no limiter slot")
    void noSectionReadIsForbidden() throws Exception {
        User merchantAccount = lockedMerchantUnlocked();
        String merchant = session(merchantAccount);
        search(merchant, "+263771234567")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Forbidden - insufficient role"))
                // The @PreAuthorize refused it: the service's own query_not_permitted never ran.
                .andExpect(jsonPath("$.data.errorCode").doesNotExist());
        assertThat(count("SELECT count(*) FROM support_access_log WHERE agent_user_uuid = CAST(?1 AS uuid)",
                merchantAccount.getUserUuid().toString())).isZero();
        // More refusals than the whole short window allows: had any of them taken a
        // slot, the last would be a 429 — every one stays the security chain's 403.
        int window = supportProperties.getLimiter().getShortWindowMax();
        for (int i = 0; i < window; i++) {
            search(merchant, "+263771234567").andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("the device-security reads are logged and counted, with an unchanged contract")
    void deviceSecurityReadsAreLogged() throws Exception {
        User merchant = lockedMerchant();
        User agentAccount = eligibleStaff("CALL_CENTER_AGENT", true);
        String agent = session(agentAccount);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/admin/device-security/customers/{m}", merchant.getPhoneNumber())
                        .header("Authorization", agent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.msisdn").value(merchant.getPhoneNumber()));
        assertThat(count("SELECT count(*) FROM support_access_log WHERE op = 'DEVICE_SECURITY_OVERVIEW' "
                + "AND agent_user_uuid = CAST(?1 AS uuid) AND outcome = 'OK' AND customer_keys LIKE ?2",
                agentAccount.getUserUuid().toString(), "%" + merchant.getPhoneNumber() + "%")).isEqualTo(1);
    }

    /** A merchant that can sign in (not locked) — to prove a non-staff session cannot search. */
    private User lockedMerchantUnlocked() {
        User m = lockedMerchant();
        m.setLockedUntil(null);
        m.setFailedLoginAttempts(0);
        return users.save(m);
    }
}
