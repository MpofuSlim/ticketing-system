package com.innbucks.userservice.integration;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserTokenState;
import com.innbucks.userservice.service.AccountSessionRevoker;
import com.innbucks.userservice.service.TokenVersionBumper;
import com.innbucks.userservice.testsupport.SessionRevocationItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A login racing a deactivation, against real Postgres row locks.
 *
 * <p>The old bump was {@code setTokenVersion(v + 1)} then a full-row save: a
 * login that loaded the account before a deactivation committed wrote
 * {@code v + 1} — and {@code active = true} — straight back over it, so the
 * account ended up active again with the login's fresh session current. Now
 * every bump is an atomic {@code UPDATE ... RETURNING}, the password step's is
 * conditional on {@code active}, {@code token_version} is not writable by an
 * entity save and {@code User} is {@code @DynamicUpdate}. Each interleaving
 * below ends deactivated, with every session the login minted already dead.
 */
class ConcurrentLoginAndDeactivateIT extends SessionRevocationItSupport {

    @Autowired TokenVersionBumper bumper;
    @Autowired AccountSessionRevoker revoker;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private UserTokenState state(Long id) {
        return users.findTokenStateById(id).orElseThrow();
    }

    @Test
    void loginBumpsFirst_thenTheDeactivationBumpsPastIt_finalIsVPlus2() throws Exception {
        User staff = staff("PRODUCT_OFFICER", false);
        long v = state(staff.getId()).version();
        CountDownLatch loginBumped = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);

        Future<Boolean> login = pool.submit(() -> tx().execute(status -> {
            User mine = users.findById(staff.getId()).orElseThrow();
            boolean ok = bumper.bumpIfActive(mine);
            loginBumped.countDown();
            await(releaseLogin);
            return ok;
        }));
        assertThat(loginBumped.await(20, TimeUnit.SECONDS)).isTrue();

        Future<?> deactivation = pool.submit(() -> tx().executeWithoutResult(status -> {
            User theirs = users.findById(staff.getId()).orElseThrow();
            revoker.revokeAll(theirs, "it_race");
        }));
        // The deactivation's atomic UPDATE is queued behind the login's row lock.
        assertThatThrownBy(() -> deactivation.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        releaseLogin.countDown();
        assertThat(login.get(20, TimeUnit.SECONDS)).isTrue();
        deactivation.get(20, TimeUnit.SECONDS);

        UserTokenState end = state(staff.getId());
        assertThat(end.version()).as("no bump lost: v+2, not v+1").isEqualTo(v + 2);
        assertThat(end.isActive()).isFalse();
    }

    @Test
    void deactivationCommitsFirst_thenTheLoginsBumpMatchesNothing() throws Exception {
        User staff = staff("PRODUCT_OFFICER", false);
        long v = state(staff.getId()).version();
        CountDownLatch loginLoaded = new CountDownLatch(1);
        CountDownLatch deactivated = new CountDownLatch(1);

        // The login read the row as active before the deactivation committed…
        Future<Boolean> login = pool.submit(() -> tx().execute(status -> {
            User stale = users.findById(staff.getId()).orElseThrow();
            assertThat(stale.isActive()).isTrue();
            loginLoaded.countDown();
            await(deactivated);
            return bumper.bumpIfActive(stale);
        }));
        assertThat(loginLoaded.await(20, TimeUnit.SECONDS)).isTrue();
        tx().executeWithoutResult(status ->
                revoker.revokeAll(users.findById(staff.getId()).orElseThrow(), "it_race"));
        deactivated.countDown();

        // …but its conditional bump re-reads the committed row and refuses.
        assertThat(login.get(20, TimeUnit.SECONDS)).isFalse();
        UserTokenState end = state(staff.getId());
        assertThat(end.version()).isEqualTo(v + 1);
        assertThat(end.isActive()).isFalse();
    }

    @Test
    void aStaleSaveOfAnotherColumn_doesNotReviveTheAccount() throws Exception {
        // e.g. a password reset that loaded the account before the deactivation
        // committed. With every column written from its snapshot it put
        // active = true back; @DynamicUpdate writes only what it changed.
        User staff = staff("PRODUCT_OFFICER", false);
        long v = state(staff.getId()).version();
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch deactivated = new CountDownLatch(1);

        Future<?> staleWriter = pool.submit(() -> tx().executeWithoutResult(status -> {
            User stale = users.findById(staff.getId()).orElseThrow();
            loaded.countDown();
            await(deactivated);
            stale.setFailedLoginAttempts(0);
            stale.setMustChangePassword(true);
            users.save(stale);
        }));
        assertThat(loaded.await(20, TimeUnit.SECONDS)).isTrue();
        tx().executeWithoutResult(status ->
                revoker.revokeAll(users.findById(staff.getId()).orElseThrow(), "it_race"));
        deactivated.countDown();
        staleWriter.get(20, TimeUnit.SECONDS);

        UserTokenState end = state(staff.getId());
        assertThat(end.isActive()).isFalse();
        assertThat(end.version()).isEqualTo(v + 1);
        assertThat(users.findById(staff.getId()).orElseThrow().isMustChangePassword()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
