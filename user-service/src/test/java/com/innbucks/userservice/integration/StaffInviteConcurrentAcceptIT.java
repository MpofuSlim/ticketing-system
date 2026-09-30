package com.innbucks.userservice.integration;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffItSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An invite link works ONCE, even when it is redeemed from several places at
 * the same instant (V44 §2.7): the claim is one conditional
 * {@code UPDATE staff_invites SET used_at = now WHERE token_hash = ? AND used_at
 * IS NULL AND revoked_at IS NULL AND expires_at > now}, so exactly one request
 * sets a password and every other one is the opaque 400 {@code invite_invalid}
 * — recorded as a replay. Real Postgres, real row locks.
 */
class StaffInviteConcurrentAcceptIT extends StaffItSupport {

    private static final int RACERS = 8;

    @Test
    void exactlyOneOfManySimultaneousAcceptsWins() throws Exception {
        String address = staffAddress();
        long id = createStaff(address, null, "CALL_CENTER_AGENT");
        String token = inviteTokenSentTo(address);
        long versionBefore = liveTokenVersion(id);

        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < RACERS; i++) {
                String password = "Racer-" + i + "-Pass-7q";
                results.add(pool.submit(() -> {
                    start.await();
                    return accept(token, password).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) statuses.add(f.get(60, TimeUnit.SECONDS));

            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 400).hasSize(RACERS - 1);
        } finally {
            pool.shutdownNow();
        }

        // One password set, one bump, one acceptance — and every loser recorded.
        User user = users.findById(id).orElseThrow();
        long matching = 0;
        for (int i = 0; i < RACERS; i++) {
            if (passwordEncoder.matches("Racer-" + i + "-Pass-7q", user.getPassword())) matching++;
        }
        assertThat(matching).isEqualTo(1);
        assertThat(user.getEmailVerifiedAt()).isNotNull();
        assertThat(liveTokenVersion(id)).isEqualTo(versionBefore + 1);
        assertThat(count("SELECT count(*) FROM staff_invites WHERE user_id = ?1 AND used_at IS NOT NULL", id))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'STAFF_INVITE_ACCEPTED' "
                + "AND target_id = ?1", String.valueOf(id))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'STAFF_INVITE_REPLAYED' "
                + "AND target_id = ?1", String.valueOf(id))).isEqualTo(RACERS - 1);

        // And the spent link stays spent.
        accept(token, "Late-Comer-Pass-7q").andReturn();
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'STAFF_INVITE_ACCEPTED' "
                + "AND target_id = ?1", String.valueOf(id))).isEqualTo(1);
    }
}
