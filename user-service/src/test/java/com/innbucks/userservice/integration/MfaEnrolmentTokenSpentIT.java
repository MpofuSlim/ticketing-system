package com.innbucks.userservice.integration;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.security.MfaTokenService;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuthService;
import com.innbucks.userservice.service.TokenVersionBumper;
import com.innbucks.userservice.testsupport.SessionRevocationItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /auth/mfa/enroll/complete} spends the ENROLLMENT mfaToken it is given
 * (T26), against real Postgres row locks.
 *
 * <p>It used to verify that token, commit the enrolment in its own transaction,
 * then mint a fresh login mfaToken bound to whatever {@code token_version} the
 * row held by then, and spend THAT. The presented token was never spent: two
 * submits of it could both pass the first check and each end in a session, and
 * a bump landing between the steps was absorbed. Now the enrolment token's own
 * {@code tv} is compare-and-set first, and the enrolment and the mint share its
 * transaction.
 */
class MfaEnrolmentTokenSpentIT extends SessionRevocationItSupport {

    @Autowired AuthService authService;
    @Autowired TokenVersionBumper bumper;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    /** Password step of an un-enrolled staff account, then enroll/start: returns {token, secret}. */
    private String[] startEnrolment(User staff) throws Exception {
        String enrolToken = data(passwordStep(staff.getEmail()).andExpect(status().isOk()))
                .at("/mfaToken").asText();
        String secret = data(mockMvc.perform(post("/auth/mfa/enroll/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaToken", enrolToken))))
                .andExpect(status().isOk()))
                .at("/secret").asText();
        return new String[] {enrolToken, secret};
    }

    private ResultActions enrolComplete(String enrolToken, String code) throws Exception {
        return mockMvc.perform(post("/auth/mfa/enroll/complete")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("mfaToken", enrolToken, "code", code))));
    }

    private long unusedBackupCodes(Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM mfa_backup_codes WHERE user_id = :id AND used_at IS NULL")
                .setParameter("id", userId)
                .getSingleResult()).longValue();
    }

    /** A six-digit code outside the verifier's +/-1 step window, so it can never happen to be right. */
    private static String wrongCode(String secret) throws Exception {
        long step = new dev.samstevens.totp.time.SystemTimeProvider().getTime() / 30;
        dev.samstevens.totp.code.DefaultCodeGenerator generator = new dev.samstevens.totp.code.DefaultCodeGenerator();
        java.util.Set<String> valid = new java.util.HashSet<>();
        for (long s = step - 2; s <= step + 2; s++) {
            valid.add(generator.generate(secret, s));
        }
        for (int candidate = 0; ; candidate++) {
            String code = String.format("%06d", candidate);
            if (!valid.contains(code)) {
                return code;
            }
        }
    }

    private boolean mfaEnabled(Long userId) {
        return (Boolean) em.createNativeQuery("SELECT mfa_enabled FROM users WHERE id = :id")
                .setParameter("id", userId)
                .getSingleResult();
    }

    @Test
    void aSecondSubmitOfTheSameEnrolmentToken_is400_andMintsNothing() throws Exception {
        User staff = staff("PRODUCT_OFFICER", false);
        String[] started = startEnrolment(staff);

        enrolComplete(started[0], totp(started[1])).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
        long afterFirst = liveTokenVersion(staff.getId());

        enrolComplete(started[0], totp(started[1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("mfaToken is invalid or expired"));

        assertThat(liveTokenVersion(staff.getId())).isEqualTo(afterFirst);
        assertThat(liveRefreshTokens(staff.getId())).isEqualTo(1L);
        assertThat(unusedBackupCodes(staff.getId()))
                .as("the refused replay must not replace the backup codes the first response showed")
                .isEqualTo(10L);
    }

    @Test
    void twoConcurrentSubmitsOfOneEnrolmentToken_exactlyOneSignsIn() throws Exception {
        User staff = staff("PRODUCT_OFFICER", false);
        String[] started = startEnrolment(staff);
        String code = totp(started[1]);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<AuthService.EnrollmentSignIn>> submits = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            submits.add(pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                return authService.completeEnrollmentAndSignIn(started[0], code, DEVICE, AuditContext.none());
            }));
        }
        go.countDown();

        int signedIn = 0;
        int refused = 0;
        List<String> shownCodes = null;
        for (Future<AuthService.EnrollmentSignIn> submit : submits) {
            try {
                AuthService.EnrollmentSignIn result = submit.get(30, TimeUnit.SECONDS);
                signedIn++;
                shownCodes = result.backupCodes();
            } catch (ExecutionException ex) {
                assertThat(ex.getCause()).isInstanceOf(MfaTokenService.InvalidMfaTokenException.class)
                        .hasMessage("mfaToken is invalid or expired");
                refused++;
            }
        }
        assertThat(signedIn).isEqualTo(1);
        assertThat(refused).isEqualTo(1);
        assertThat(liveRefreshTokens(staff.getId())).isEqualTo(1L);
        assertThat(shownCodes).hasSize(10);
        assertThat(unusedBackupCodes(staff.getId()))
                .as("the loser's enrolment rolled back — the stored codes are the ones the winner showed")
                .isEqualTo(10L);
    }

    @Test
    void aBumpBetweenThePasswordStepAndEnrolComplete_endsTheEnrolment_andEnrolsNothing() throws Exception {
        User staff = staff("PRODUCT_OFFICER", false);
        String[] started = startEnrolment(staff);

        // Any bump — a role change, an admin MFA reset, a password reset.
        tx().executeWithoutResult(status -> bumper.bump(users.findById(staff.getId()).orElseThrow()));

        enrolComplete(started[0], totp(started[1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("mfaToken is invalid or expired"));
        assertThat(mfaEnabled(staff.getId())).isFalse();
        assertThat(unusedBackupCodes(staff.getId())).isZero();
        assertThat(liveRefreshTokens(staff.getId())).isZero();
    }

    @Test
    void aWrongCode_changesNothing_andTheSameTokenWorksForTheRetry() throws Exception {
        User staff = staff("PRODUCT_OFFICER", false);
        String[] started = startEnrolment(staff);
        long before = liveTokenVersion(staff.getId());

        enrolComplete(started[0], wrongCode(started[1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That code didn't match. Try the next one your app shows."));
        assertThat(liveTokenVersion(staff.getId()))
                .as("the spend rolled back with the refused enrolment")
                .isEqualTo(before);
        assertThat(mfaEnabled(staff.getId())).isFalse();

        enrolComplete(started[0], totp(started[1])).andExpect(status().isOk());
        assertThat(mfaEnabled(staff.getId())).isTrue();
    }
}
