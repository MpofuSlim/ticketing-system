package com.innbucks.userservice.integration;

import com.innbucks.userservice.testsupport.StaffItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What makes an invite link DEAD, proved against real Postgres (V44 §2.7).
 * {@code StaffInviteRepository.consume} is one conditional UPDATE —
 * {@code used_at IS NULL AND revoked_at IS NULL AND expires_at > now} — and each
 * of its three conditions is load-bearing: a used link, a SUPERSEDED (revoked)
 * link and an expired link are all the opaque 400 {@code invite_invalid}, and
 * the refusal is audited {@code STAFF_INVITE_REPLAYED} with the reason. And a
 * burst of concurrent resends leaves exactly ONE live link (the account row lock
 * serialises them).
 */
class StaffInviteLinkLifecycleIT extends StaffItSupport {

    private static final String NEW_PASSWORD = "Lifecycle-Staff-Pass-7q";

    private void invalid(String token) throws Exception {
        accept(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "This invite link is no longer valid. Ask your administrator to send a new one."))
                .andExpect(jsonPath("$.data.errorCode").value("invite_invalid"));
    }

    private long replays(long userId, String reason) {
        return count("SELECT count(*) FROM audit_events WHERE event_type = 'STAFF_INVITE_REPLAYED' "
                + "AND target_id = ?1 AND failure_reason = ?2", String.valueOf(userId), reason);
    }

    private org.springframework.test.web.servlet.ResultActions resend(long id) throws Exception {
        return mockMvc.perform(post("/admin/staff/{id}/resend-invite", id).with(authentication(staffAdmin()))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    void aSupersededLinkIsDead_andTheReplayIsAudited() throws Exception {
        String address = staffAddress();
        long id = createStaff(address, null, "CALL_CENTER_AGENT");
        String first = inviteTokenSentTo(address);
        resend(id).andExpect(status().isOk());
        String second = nthInviteTokenSentTo(address, 2);
        assertThat(second).isNotEqualTo(first);

        invalid(first);
        assertThat(replays(id, "revoked:SUPERSEDED")).isEqualTo(1);
        // The superseding link still works.
        accept(second, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void anExpiredLinkIsDead() throws Exception {
        String address = staffAddress();
        long id = createStaff(address, null, "CALL_CENTER_AGENT");
        String token = inviteTokenSentTo(address);
        tx().executeWithoutResult(s -> em.createNativeQuery(
                        "UPDATE staff_invites SET expires_at = now() AT TIME ZONE 'UTC' - interval '1 hour' "
                                + "WHERE user_id = ?1")
                .setParameter(1, id).executeUpdate());

        invalid(token);
        assertThat(replays(id, "expired")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM staff_invites WHERE user_id = ?1 AND used_at IS NOT NULL", id))
                .isZero();
    }

    @Test
    void concurrentResendsLeaveExactlyOneLiveLink() throws Exception {
        String address = staffAddress();
        long id = createStaff(address, null, "CALL_CENTER_AGENT");
        inviteTokenSentTo(address);
        int racers = 4;   // create + 4 resends = the 5-per-24h quota exactly

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return resend(id).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> f : results) assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM staff_invites WHERE user_id = ?1", id)).isEqualTo(racers + 1);
        assertThat(count("SELECT count(*) FROM staff_invites WHERE user_id = ?1 "
                + "AND used_at IS NULL AND revoked_at IS NULL", id)).isEqualTo(1);
    }
}
