package com.innbucks.userservice.devicesecurity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One phone per number under concurrency, through the real HTTP stack, security
 * chain and schema: two requests that each write the number's phones are released
 * at the same instant (a {@link CyclicBarrier} over two threads sharing one
 * {@link MockMvc}) and the end state is checked against the invariant the feature
 * exists for — never two signed-in phones on one number.
 *
 * <p>The broker's login result and {@code /otp/verify} queue on the number's lock
 * ({@code NumberLock}); the sign-in decision takes no lock on purpose and is refused
 * by the device row's {@code @Version} instead (503 "try again shortly"). Which side
 * wins a round is up to the scheduler, so every scenario runs {@link #ROUNDS} rounds
 * on a fresh number, accepts each outcome the design allows, asserts the end state
 * that outcome must leave, and logs how many rounds took each branch. No round may
 * end with two TRUSTED rows, whichever branch it took.
 *
 * <p>The last two cases are deterministic rather than raced: the test holds the number's
 * lock itself ({@code SELECT … FOR UPDATE} on the profile row, from a connection of its
 * own) to pin which requests take it and what each does when it cannot have it — wait
 * and go through, refuse with nothing spent, or drop best-effort bookkeeping and carry on.
 *
 * <p>Refusals are asserted with their EXACT status and errorCode; anything else —
 * a 500 above all — fails the round.
 */
@SpringBootTest
@ActiveProfiles("test")
class SingleDeviceConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(SingleDeviceConcurrencyTest.class);

    static final String BROKER_KEY = "test-broker-key-0123456789abcdef0123456789";
    static final String USSD_KEY = "test-ussd-key-0123456789abcdef0123456789abc";

    /** Rounds per scenario, each on a fresh number. */
    static final int ROUNDS = 8;

    /**
     * Per round of the two mixed races, how many milliseconds after the barrier one side
     * waits before it sends: positive holds back the second request (the bind), negative
     * the first. Released together, the bind's short transaction reaches the database
     * well ahead of a sign-in decision (measured: 7 of 8 renewals answered TOKEN, and
     * OTP_REQUIRED never), so every round would sample the same interleaving. The offsets
     * are shorter than either request, so the two still overlap; they only move where.
     */
    static final int[] STAGGER_MS = {0, 0, 5, -5, 20, -20, 40, -40};

    static final String TOKEN_LINE = "Enter your PIN to continue.";
    static final String TOKEN_LINE_SIGNING_OUT = "Enter your PIN to continue. Any other phone will be signed out of InnBucks.";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("device-security.enabled", () -> "true");
        r.add("device-security.production", () -> "false");
        r.add("device-security.enforce.otp", () -> "true");
        r.add("device-security.enforce.blocks", () -> "true");
        r.add("device-security.enforce.bans", () -> "true");
        r.add("device-security.broker-api-key", () -> BROKER_KEY);
        r.add("device-security.ussd-api-key", () -> USSD_KEY);
        r.add("device-security.ticket.private-key", TestKeys::privatePem);
        r.add("device-security.support-phone", () -> "0867 700 0000");
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceSecurityProperties properties;

    @MockitoBean StagingClientServiceClient staging;
    @MockitoBean WhatsAppNotificationClient whatsApp;
    @MockitoBean SmsNotificationClient sms;

    MockMvc mvc;
    ExecutorService pool;

    /**
     * The address the broker reports for this round. A fresh one per round keeps the
     * per-address OTP ceiling (30 an hour) out of a test that opens up to three codes
     * a round; it is written before any race starts, so the workers see it.
     */
    volatile String ip;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.execute("TRUNCATE customer_devices, customer_security_profiles, device_otp_challenges, "
                + "device_login_tickets, device_security_events, device_wide_bans, device_sign_in_locations CASCADE");
        properties.setEnabled(true);
        properties.getEnforce().setOtp(true);
        properties.getEnforce().setBlocks(true);
        properties.getEnforce().setBans(true);
        properties.getTrust().setSingleDevice(true);
        when(staging.isConfigured()).thenReturn(true);
        when(staging.token()).thenReturn(new StagingClientServiceClient.ClientServiceToken("staging-client-token",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)));
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        // The properties bean outlives this class: never leave the rule on for the next one.
        properties.getTrust().setSingleDevice(false);
    }

    // =====================================================================================
    // Two binds at once
    // =====================================================================================

    @Test
    @DisplayName("two phones of one number reporting a successful PIN at the same instant: both answered 200, "
            + "exactly one ends TRUSTED, the other REVOKED/REPLACED with its ticket voided, and neither login result "
            + "ever needs its retry — every round")
    void concurrentBinds_leaveExactlyOneTrusted() throws Exception {
        int aWon = 0;
        int bWon = 0;
        SignInLog signInLog = SignInLog.attach();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String msisdn = startRound(round);
                Verified a = verifiedPhone(msisdn, UUID.randomUUID().toString());
                Verified b = verifiedPhone(msisdn, UUID.randomUUID().toString());
                // Neither has ever been bound, so neither TOKEN warns about signing a phone out.
                assertThat(a.message()).isEqualTo(TOKEN_LINE);
                assertThat(b.message()).isEqualTo(TOKEN_LINE);

                // Released together every round; the submission order alternates, so the phone
                // that verified first is not always the one the scheduler happens to favour.
                Verified first = round % 2 == 0 ? a : b;
                Verified second = round % 2 == 0 ? b : a;
                signInLog.clear();
                Hit[] hits = race(() -> hit(loginResultRequest(first.jti(), "SUCCESS")),
                        () -> hit(loginResultRequest(second.jti(), "SUCCESS")), 0);

                String what = "round " + round + ": " + hits[0] + " / " + hits[1];
                // The number's lock queues the two binds: the second waits for the first and
                // reads fresh rows, so neither fails on a version and falls into the retry
                // (measured: 0 retries in 40 rounds).
                assertThat(signInLog.loginResultRetries()).as(what).isZero();
                for (Hit h : hits) {
                    assertThat(h.status()).as(what).isEqualTo(200);
                    assertThat((Boolean) JsonPath.read(h.body(), "$.data.recorded")).as(what).isTrue();
                }
                // The loser waited for the number's lock, found its ticket voided by the winner's
                // sign-out, recorded the outcome and bound nothing.
                assertThat(List.of(hits[0].deviceState(), hits[1].deviceState())).as(what)
                        .containsExactlyInAnyOrder("TRUSTED", "REVOKED");
                boolean firstWins = "TRUSTED".equals(hits[0].deviceState());
                Verified winner = firstWins ? first : second;
                Verified loser = firstWins ? second : first;
                if (winner == a) aWon++; else bWon++;

                assertThat(trustedRows(msisdn)).as(what).isEqualTo(1);
                assertThat(row(winner)).as(what).isEqualTo(new DeviceRow("TRUSTED", null));
                assertThat(row(loser)).as(what).isEqualTo(new DeviceRow("REVOKED", DeviceEnforcement.REPLACED));
                assertThat(voidedAt(loser.jti())).as(what).isNotNull();
                assertThat(voidedAt(winner.jti())).as(what).isNull();
                // Signed out once, by the winner — never twice, never by both.
                assertThat(replacedNotes(msisdn)).as(what).containsExactly("Signed out by phone " + winner.deviceId());
            }
        } finally {
            signInLog.detach();
        }
        log.info("Concurrent binds over {} rounds: the phone that verified first won {}, the other {}", ROUNDS, aWon,
                bWon);
    }

    // =====================================================================================
    // The old phone renewing while the new one binds
    // =====================================================================================

    @Test
    @DisplayName("the signed-in phone renewing silently while a new phone binds: the bind always wins first time "
            + "(200, TRUSTED, no retry — it locks the old phone instead of failing on its version); the renewal is "
            + "TOKEN, OTP_REQUIRED or 503 — never 500 — and never leaves two TRUSTED rows; a TOKEN it got is refused "
            + "INVALID at redeem")
    void renewalOfTheOldPhoneRacingTheBind_neverLeavesTwoTrusted() throws Exception {
        int token = 0;
        int otpRequired = 0;
        int versionClash = 0;
        int ticketVoidedByTheBind = 0;
        int ticketEscapedTheVoid = 0;
        SignInLog signInLog = SignInLog.attach();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String msisdn = startRound(round);
                Verified a = trustedPhone(msisdn, UUID.randomUUID().toString());
                Verified b = verifiedPhone(msisdn, UUID.randomUUID().toString());
                // A has been the customer's phone: B's TOKEN says it will be signed out.
                assertThat(b.message()).isEqualTo(TOKEN_LINE_SIGNING_OUT);

                signInLog.clear();
                Hit[] hits = race(() -> hit(clientServiceRequest(msisdn, a.install(), "RENEW")),
                        () -> hit(loginResultRequest(b.jti(), "SUCCESS")), STAGGER_MS[round]);
                Hit renew = hits[0];
                Hit bind = hits[1];
                String what = "round " + round + ": renew " + renew + " / bind " + bind;
                // The sign-out locks A (FOR UPDATE) and re-reads it, so a renewal that wrote A
                // first makes the bind wait, never fail on A's @Version and retry (measured: 0
                // retries in 40 rounds, across every renewal outcome below).
                assertThat(signInLog.loginResultRetries()).as(what).isZero();

                assertThat(bind.status()).as(what).isEqualTo(200);
                assertThat((Boolean) JsonPath.read(bind.body(), "$.data.recorded")).as(what).isTrue();
                assertThat(bind.deviceState()).as(what).isEqualTo("TRUSTED");

                String renewTicket = null;
                DeviceRow aAfter;
                if (renew.status() == 200 && "TOKEN".equals(renew.decision())) {
                    // A's decision committed first (a bind that reached A meanwhile waited on
                    // A's row lock and read it fresh): B then signed A out.
                    token++;
                    renewTicket = JsonPath.read(renew.body(), "$.data.loginTicket");
                    assertThat((String) JsonPath.read(renew.body(), "$.data.trust.state")).as(what).isEqualTo("TRUSTED");
                    aAfter = new DeviceRow("REVOKED", DeviceEnforcement.REPLACED);
                } else if (renew.status() == 200 && "OTP_REQUIRED".equals(renew.decision())) {
                    // B's bind committed first: A came back signed out and is asked for a code.
                    otpRequired++;
                    assertThat((String) JsonPath.read(renew.body(), "$.message")).as(what)
                            .isEqualTo("Let's confirm it's you on this phone.");
                    assertThat((String) JsonPath.read(renew.body(), "$.data.reason")).as(what).isEqualTo("NEW_DEVICE");
                    aAfter = new DeviceRow("NEW", DeviceEnforcement.REPLACED);
                } else if (renew.status() == 503) {
                    // A's decision read A as TRUSTED, B's bind revoked it first, and A's write lost
                    // on the row's @Version: nothing of A's decision survives.
                    versionClash++;
                    assertThat(renew.errorCode()).as(what).isEqualTo("temporarily_unavailable");
                    aAfter = new DeviceRow("REVOKED", DeviceEnforcement.REPLACED);
                } else {
                    fail("The renewal may answer 200 TOKEN, 200 OTP_REQUIRED or 503 temporarily_unavailable, got " + what);
                    return;
                }

                assertThat(trustedRows(msisdn)).as(what).isEqualTo(1);
                assertThat(row(b)).as(what).isEqualTo(new DeviceRow("TRUSTED", null));
                assertThat(row(a)).as(what).isEqualTo(aAfter);
                assertThat(replacedNotes(msisdn)).as(what).containsExactly("Signed out by phone " + b.deviceId());

                if (renewTicket != null) {
                    String jti = jti(renewTicket);
                    // Minted before B's sign-out: voided by it. Minted after (the decision commits,
                    // the ticket is minted outside it): the redeem check catches it.
                    if (voidedAt(jti) != null) ticketVoidedByTheBind++; else ticketEscapedTheVoid++;
                    redeem(renewTicket, msisdn, a.install(), "SIGN_IN")
                            .andExpect(jsonPath("$.data.valid").value(false))
                            .andExpect(jsonPath("$.data.reason").value("INVALID"));
                    assertThat(voidedAt(jti)).as(what).isNotNull();
                    // The capture is live on this logger: the refusal says why, in the log only.
                    assertThat(signInLog.messages(Level.WARN)).as(what).contains("Login ticket refused reason=INVALID "
                            + "(phone no longer signed in) msisdn=" + DeviceIdentity.logMask(msisdn));
                }
            }
        } finally {
            signInLog.detach();
        }
        log.info("Renewal racing a bind over {} rounds: renewal TOKEN {} (ticket voided by the bind {}, escaped it {}), "
                        + "OTP_REQUIRED {}, 503 version clash {}", ROUNDS, token, ticketVoidedByTheBind,
                ticketEscapedTheVoid, otpRequired, versionClash);
    }

    // =====================================================================================
    // A third phone verifying its code while the second binds
    // =====================================================================================

    @Test
    @DisplayName("a third phone verifying its code while a second phone binds: the bind is 200 TRUSTED, the verify "
            + "200 TOKEN, 410 or 503 — never 500 — at most one TRUSTED row; a third phone left PENDING_PIN then "
            + "binds as the only TRUSTED phone, one signed out binds nothing")
    void verifyRacingTheBind_neverLeavesTwoHolders() throws Exception {
        int verifiedAfterTheBind = 0;
        int verifiedBeforeTheBind = 0;
        int ticketVoidedByTheBind = 0;
        int ticketEscapedTheVoid = 0;
        int gone = 0;
        int lockWaitRanOut = 0;
        for (int round = 0; round < ROUNDS; round++) {
            String msisdn = startRound(round);
            Verified a = trustedPhone(msisdn, UUID.randomUUID().toString());
            Verified b = verifiedPhone(msisdn, UUID.randomUUID().toString());
            String installC = UUID.randomUUID().toString();
            String asked = clientService(msisdn, installC, "SIGN_IN");
            assertThat((String) JsonPath.read(asked, "$.data.decision")).isEqualTo("OTP_REQUIRED");
            String challengeC = JsonPath.read(asked, "$.data.challengeId");
            String codeC = sendAndCapture(challengeC, "WHATSAPP", installC, msisdn);

            Hit[] hits = race(() -> hit(verifyRequest(challengeC, codeC, installC)),
                    () -> hit(loginResultRequest(b.jti(), "SUCCESS")), STAGGER_MS[round]);
            Hit verified = hits[0];
            Hit bind = hits[1];
            String what = "round " + round + ": verify " + verified + " / bind " + bind;

            // A code never revokes anything, so B's ticket is never voided under it.
            assertThat(bind.status()).as(what).isEqualTo(200);
            assertThat((Boolean) JsonPath.read(bind.body(), "$.data.recorded")).as(what).isTrue();
            assertThat(bind.deviceState()).as(what).isEqualTo("TRUSTED");

            String ticketC = null;
            if (verified.status() == 200) {
                assertThat(verified.decision()).as(what).isEqualTo("TOKEN");
                assertThat((String) JsonPath.read(verified.body(), "$.data.trust.state")).as(what).isEqualTo("PENDING_PIN");
                // Whichever ran first, a phone in use (A, or B once bound) stands to be signed out.
                assertThat((String) JsonPath.read(verified.body(), "$.message")).as(what).isEqualTo(TOKEN_LINE_SIGNING_OUT);
                ticketC = JsonPath.read(verified.body(), "$.data.loginTicket");
            } else if (verified.status() == 410) {
                gone++;
                assertThat(verified.errorCode()).as(what).isEqualTo("challenge_expired");
            } else if (verified.status() == 503) {
                lockWaitRanOut++;
                assertThat(verified.errorCode()).as(what).isEqualTo("temporarily_unavailable");
            } else {
                fail("The verify may answer 200 TOKEN, 410 challenge_expired or 503 temporarily_unavailable, got "
                        + what);
                return;
            }

            assertThat(trustedRows(msisdn)).as(what).isLessThanOrEqualTo(1);
            assertThat(row(b)).as(what).isEqualTo(new DeviceRow("TRUSTED", null));
            assertThat(row(a)).as(what).isEqualTo(new DeviceRow("REVOKED", DeviceEnforcement.REPLACED));
            DeviceRow c = row(msisdn, installC);

            if ("PENDING_PIN".equals(c.state())) {
                // B bound first and C (NEW then) was not its to sign out; C's code came after.
                verifiedAfterTheBind++;
                assertThat(ticketC).as(what).isNotNull();
                assertThat(c).as(what).isEqualTo(new DeviceRow("PENDING_PIN", null));
                loginResult(jti(ticketC), "SUCCESS")
                        .andExpect(jsonPath("$.data.recorded").value(true))
                        .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));
                assertThat(trustedRows(msisdn)).as(what).isEqualTo(1);
                assertThat(row(msisdn, installC)).as(what).isEqualTo(new DeviceRow("TRUSTED", null));
                assertThat(row(b)).as(what).isEqualTo(new DeviceRow("REVOKED", DeviceEnforcement.REPLACED));
            } else if (ticketC != null) {
                // C's code went first; B's bind then signed C out with A. C's own ticket,
                // voided or minted just after the void, binds nothing.
                verifiedBeforeTheBind++;
                assertThat(c).as(what).isEqualTo(new DeviceRow("REVOKED", DeviceEnforcement.REPLACED));
                if (voidedAt(jti(ticketC)) != null) ticketVoidedByTheBind++; else ticketEscapedTheVoid++;
                loginResult(jti(ticketC), "SUCCESS")
                        .andExpect(jsonPath("$.data.recorded").value(true))
                        .andExpect(jsonPath("$.data.deviceState").value("REVOKED"));
                assertThat(trustedRows(msisdn)).as(what).isEqualTo(1);
                assertThat(row(b)).as(what).isEqualTo(new DeviceRow("TRUSTED", null));
            } else {
                // The code was never accepted: C is still the attempt it was.
                assertThat(c).as(what).isEqualTo(new DeviceRow("NEW", null));
            }
        }
        log.info("Verify racing a bind over {} rounds: verified after the bind {}, verified before it {} (ticket voided "
                        + "by the bind {}, escaped it {}), 410 {}, 503 lock wait {}", ROUNDS, verifiedAfterTheBind,
                verifiedBeforeTheBind, ticketVoidedByTheBind, ticketEscapedTheVoid, gone, lockWaitRanOut);
    }

    // =====================================================================================
    // The number's lock, held by someone else
    // =====================================================================================

    @Test
    @DisplayName("the number's lock held elsewhere: /otp/verify waits its 500ms and answers 503 temporarily_unavailable "
            + "with the code unspent; the broker's login result queues behind it and binds the phone (200, TRUSTED) "
            + "the moment it is released, without a retry")
    void numberLock_isHeldBy_otpVerify_andLoginResult() throws Exception {
        String msisdn = startRound(0);
        String installB = UUID.randomUUID().toString();
        String asked = clientService(msisdn, installB, "SIGN_IN");
        assertThat((String) JsonPath.read(asked, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        String challenge = JsonPath.read(asked, "$.data.challengeId");
        String code = sendAndCapture(challenge, "WHATSAPP", installB, msisdn);
        ChallengeRow sent = challengeRow(challenge);
        assertThat(sent.status()).isEqualTo("OPEN");

        DataSource dataSource = jdbc.getDataSource();
        assertThat(dataSource).isNotNull();
        SignInLog signInLog = SignInLog.attach();
        Connection held = dataSource.getConnection();
        try {
            held.setAutoCommit(false);

            // 1. A bind of another phone holds the number: the code waits 500ms, then gives up.
            lockNumber(held, msisdn);
            long started = System.nanoTime();
            Hit refused = hit(verifyRequest(challenge, code, installB));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(refused.status()).as(refused.toString()).isEqualTo(503);
            assertThat(refused.errorCode()).isEqualTo("temporarily_unavailable");
            assertThat((String) JsonPath.read(refused.body(), "$.message"))
                    .isEqualTo("We can't complete this right now. Please try again shortly.");
            assertThat(waitedMs).as("answered after the 500ms lock wait, not held for the pool")
                    .isGreaterThanOrEqualTo(450).isLessThan(3_000);
            // Nothing was spent: the code is still open with every attempt, the phone is the
            // attempt it was, and no ticket was minted.
            assertThat(challengeRow(challenge)).isEqualTo(sent);
            assertThat(row(msisdn, installB)).isEqualTo(new DeviceRow("NEW", null));
            assertThat(ticketCount(msisdn)).isZero();
            held.rollback();

            // 2. Released: the same code now verifies.
            String token = verifyOtp(challenge, code, installB);
            assertThat((String) JsonPath.read(token, "$.data.decision")).isEqualTo("TOKEN");
            assertThat((String) JsonPath.read(token, "$.data.trust.state")).isEqualTo("PENDING_PIN");
            assertThat(challengeRow(challenge).status()).isEqualTo("VERIFIED");
            String jti = jti(JsonPath.read(token, "$.data.loginTicket"));

            // 3. Held again: the broker's login result queues on the number instead of binding.
            lockNumber(held, msisdn);
            signInLog.clear();
            long queuedAt = System.nanoTime();
            Future<Hit> loginResult = pool.submit(() -> hit(loginResultRequest(jti, "SUCCESS")));
            assertThatThrownBy(() -> loginResult.get(600, TimeUnit.MILLISECONDS))
                    .as("the login result must wait for the number's lock")
                    .isInstanceOf(TimeoutException.class);
            assertThat(ticketOutcome(jti)).as("nothing recorded while it waits").isNull();
            assertThat(row(msisdn, installB)).isEqualTo(new DeviceRow("PENDING_PIN", null));
            held.rollback();

            Hit bound = loginResult.get(10, TimeUnit.SECONDS);
            long queuedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAt);
            assertThat(bound.status()).as(bound.toString()).isEqualTo(200);
            assertThat((Boolean) JsonPath.read(bound.body(), "$.data.recorded")).isTrue();
            assertThat(bound.deviceState()).isEqualTo("TRUSTED");
            assertThat((String) JsonPath.read(bound.body(), "$.data.outcome")).isEqualTo("SUCCESS");
            assertThat(queuedMs).as("bound once released, inside its first 2s lock wait").isLessThan(2_000);
            // It waited and went through: its first attempt did not time out into the retry.
            assertThat(signInLog.loginResultRetries()).isZero();
            assertThat(ticketOutcome(jti)).isEqualTo("SUCCESS");
            assertThat(row(msisdn, installB)).isEqualTo(new DeviceRow("TRUSTED", null));
            assertThat(trustedRows(msisdn)).isEqualTo(1);
        } finally {
            signInLog.detach();
            try {
                held.rollback();
            } finally {
                held.close();
            }
        }
    }

    @Test
    @DisplayName("the number's lock held elsewhere while a code is sent: /otp/send still answers 200 within its 1s "
            + "lock wait and the WhatsApp code goes out; the channel bookkeeping is logged and dropped, never thrown, "
            + "so preferred_channel is left as it was until the code is verified")
    void rememberChannel_neverFailsTheSend() throws Exception {
        String msisdn = startRound(0);
        String install = UUID.randomUUID().toString();
        String asked = clientService(msisdn, install, "SIGN_IN");
        assertThat((String) JsonPath.read(asked, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        String challenge = JsonPath.read(asked, "$.data.challengeId");
        // The customer chose SMS last time: a send that wrote WHATSAPP would be visible.
        jdbc.update("UPDATE customer_security_profiles SET preferred_channel = 'SMS' WHERE msisdn = ?", msisdn);
        ProfileRow before = profileRow(msisdn);
        assertThat(before.preferredChannel()).isEqualTo("SMS");

        DataSource dataSource = jdbc.getDataSource();
        assertThat(dataSource).isNotNull();
        SignInLog signInLog = SignInLog.attach();
        Connection held = dataSource.getConnection();
        try {
            held.setAutoCommit(false);
            lockNumber(held, msisdn);

            long started = System.nanoTime();
            Hit sent = hit(broker(post("/auth/client-service/otp/send"), install)
                    .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"WHATSAPP\"}"));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            String masked = "+263 77 *** **" + msisdn.substring(msisdn.length() - 2);
            assertThat(sent.status()).as(sent.toString()).isEqualTo(200);
            assertThat((String) JsonPath.read(sent.body(), "$.data.challengeId")).isEqualTo(challenge);
            assertThat((String) JsonPath.read(sent.body(), "$.data.channel")).isEqualTo("WHATSAPP");
            assertThat((String) JsonPath.read(sent.body(), "$.data.destinationMasked")).isEqualTo(masked);
            assertThat((String) JsonPath.read(sent.body(), "$.message"))
                    .isEqualTo("We've sent a 6-digit code to your WhatsApp on " + masked + ".");
            // It did try the lock (the 1s wait), and gave up on the bookkeeping, not the send.
            assertThat(tookMs).isGreaterThanOrEqualTo(900).isLessThan(3_000);
            // The 1s lock_timeout running out (SQLSTATE 55P03), as Spring translates it.
            assertThat(signInLog.messages(Level.WARN)).containsExactly(
                    "Could not remember the OTP channel for challenge " + challenge + ": CannotAcquireLockException");

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(whatsApp, times(1)).sendCustomNotification(eq(msisdn), text.capture());
            String code = code(text.getAllValues());
            assertThat(text.getValue()).isEqualTo("InnBucks code: " + code + ". It expires in 5 minutes. Never share it "
                    + "— InnBucks will never ask for it. Using it signs InnBucks out on any other phone.");
            // Only to this number: an earlier test's async notice may still land on the mock.
            verify(sms, never()).sendSms(eq(msisdn), anyString(), anyString());
        } finally {
            signInLog.detach();
            try {
                held.rollback();
            } finally {
                held.close();
            }
        }

        // Released: the send wrote nothing to the profile, not even its version.
        assertThat(profileRow(msisdn)).isEqualTo(before);
        // The code that went out is the code the send committed, and verifying it records
        // the channel the customer actually used.
        String token = verifyOtp(challenge, lastCode(msisdn), install);
        assertThat((String) JsonPath.read(token, "$.data.decision")).isEqualTo("TOKEN");
        assertThat(profileRow(msisdn).preferredChannel()).isEqualTo("WHATSAPP");
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    /**
     * Captures what {@link DeviceSignInService} logs, at INFO and above, while attached —
     * in particular the login result's one retry ("…; retrying once"). The logger's level
     * is pinned to INFO for the duration, so a quieter configuration can never turn
     * "the retry never ran" into an assertion that cannot fail. {@link #detach()} restores
     * both; call it in a {@code finally}.
     */
    private static final class SignInLog {
        private final ch.qos.logback.classic.Logger logger;
        private final Level levelBefore;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private SignInLog() {
            logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeviceSignInService.class);
            levelBefore = logger.getLevel();
        }

        static SignInLog attach() {
            SignInLog l = new SignInLog();
            if (!l.logger.isInfoEnabled()) l.logger.setLevel(Level.INFO);
            l.appender.start();
            l.logger.addAppender(l.appender);
            return l;
        }

        void detach() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(levelBefore);
        }

        void clear() {
            synchronized (appender) {
                appender.list.clear();
            }
        }

        /** Formatted messages, oldest first. */
        List<String> messages() {
            synchronized (appender) {
                return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            }
        }

        /** Formatted messages logged at exactly {@code level}, oldest first. */
        List<String> messages(Level level) {
            synchronized (appender) {
                return appender.list.stream().filter(e -> e.getLevel() == level)
                        .map(ILoggingEvent::getFormattedMessage).toList();
            }
        }

        int loginResultRetries() {
            return (int) messages().stream().filter(m -> m.startsWith("Login result for ticket ")
                    && m.endsWith("; retrying once")).count();
        }
    }

    /**
     * Takes the number's lock the way a concurrent bind does — its profile row
     * {@code FOR UPDATE} — on a connection of the test's own, held until that
     * connection rolls back.
     */
    private static void lockNumber(Connection held, String msisdn) throws SQLException {
        try (PreparedStatement ps = held.prepareStatement(
                "SELECT 1 FROM customer_security_profiles WHERE msisdn = ? FOR UPDATE")) {
            ps.setString(1, msisdn);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("the number's profile row exists to be locked").isTrue();
            }
        }
    }

    private record ChallengeRow(String status, int attemptsLeft, int sendCount, Timestamp closedAt) {
    }

    private ChallengeRow challengeRow(String challengeId) {
        return jdbc.queryForObject("SELECT status, attempts_left, send_count, closed_at FROM device_otp_challenges "
                        + "WHERE id = ?", (rs, i) -> new ChallengeRow(rs.getString("status"), rs.getInt("attempts_left"),
                        rs.getInt("send_count"), rs.getTimestamp("closed_at")), challengeId);
    }

    private record ProfileRow(String preferredChannel, long version, Timestamp updatedAt) {
    }

    private ProfileRow profileRow(String msisdn) {
        return jdbc.queryForObject("SELECT preferred_channel, version, updated_at FROM customer_security_profiles "
                        + "WHERE msisdn = ?", (rs, i) -> new ProfileRow(rs.getString("preferred_channel"),
                        rs.getLong("version"), rs.getTimestamp("updated_at")), msisdn);
    }

    private int ticketCount(String msisdn) {
        return jdbc.queryForObject("SELECT count(*) FROM device_login_tickets WHERE msisdn = ?", Integer.class, msisdn);
    }

    private String ticketOutcome(String jti) {
        return jdbc.queryForObject("SELECT outcome FROM device_login_tickets WHERE jti = ?", String.class, jti);
    }

    /** One response of a race: its status and body, read on the worker thread. */
    private record Hit(int status, String body) {
        String deviceState() {
            return JsonPath.read(body, "$.data.deviceState");
        }

        String decision() {
            return JsonPath.read(body, "$.data.decision");
        }

        String errorCode() {
            return JsonPath.read(body, "$.data.errorCode");
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    /** A phone of the number after its code: PENDING_PIN, with the ticket its TOKEN carried. */
    private record Verified(String install, UUID deviceId, String ticket, String jti, String message) {
    }

    private record DeviceRow(String state, String reason) {
    }

    /** Starts a round on a fresh number from a fresh broker-reported address. */
    private String startRound(int round) {
        ip = "41.221.147." + (10 + round);
        return number();
    }

    /**
     * Releases both requests from two threads at one barrier and returns their
     * responses in argument order; {@code staggerMs} holds one side back (see
     * {@link #STAGGER_MS}). A request that throws (an unhandled exception escaping
     * MockMvc, i.e. a 500) fails the test through {@link Future#get}.
     */
    private Hit[] race(Callable<Hit> first, Callable<Hit> second, int staggerMs) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Future<Hit> one = pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            if (staggerMs < 0) Thread.sleep(-staggerMs);
            return first.call();
        });
        Future<Hit> two = pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            if (staggerMs > 0) Thread.sleep(staggerMs);
            return second.call();
        });
        return new Hit[]{one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)};
    }

    private Hit hit(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse r = mvc.perform(request).andReturn().getResponse();
        return new Hit(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }

    /** A phone taken through the code to PENDING_PIN. */
    private Verified verifiedPhone(String msisdn, String install) throws Exception {
        String asked = clientService(msisdn, install, "SIGN_IN");
        assertThat((String) JsonPath.read(asked, "$.data.decision")).isEqualTo("OTP_REQUIRED");
        String challenge = JsonPath.read(asked, "$.data.challengeId");
        String token = verifyOtp(challenge, sendAndCapture(challenge, "WHATSAPP", install, msisdn), install);
        assertThat((String) JsonPath.read(token, "$.data.decision")).isEqualTo("TOKEN");
        assertThat((String) JsonPath.read(token, "$.data.trust.state")).isEqualTo("PENDING_PIN");
        String ticket = JsonPath.read(token, "$.data.loginTicket");
        return new Verified(install, UUID.fromString(JsonPath.read(token, "$.data.trust.deviceId")), ticket, jti(ticket),
                JsonPath.read(token, "$.message"));
    }

    /** A phone taken all the way to TRUSTED. */
    private Verified trustedPhone(String msisdn, String install) throws Exception {
        Verified v = verifiedPhone(msisdn, install);
        loginResult(v.jti(), "SUCCESS")
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.deviceState").value("TRUSTED"));
        return v;
    }

    private String clientService(String msisdn, String install, String context) throws Exception {
        return mvc.perform(clientServiceRequest(msisdn, install, context))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MockHttpServletRequestBuilder clientServiceRequest(String msisdn, String install, String context) {
        return broker(post("/auth/client-service"), install).content(signInBody(msisdn, install, context));
    }

    private static String signInBody(String msisdn, String install, String context) {
        return """
                {"requestId":"%s","purpose":"SIGN_IN","context":"%s","msisdn":"%s",
                 "device":{"installId":"%s","platform":"android","osVersion":"15","model":"SM-A155F","manufacturer":"samsung","appVersion":"2.4.0"},
                 "integrity":{"source":"freerasp","threats":[]},
                 "location":{"status":"GRANTED","lat":-17.825,"lng":31.053,"accuracyM":35,"mocked":false}}
                """.formatted(UUID.randomUUID(), context, msisdn, install);
    }

    private String sendAndCapture(String challenge, String channel, String install, String msisdn) throws Exception {
        mvc.perform(broker(post("/auth/client-service/otp/send"), install)
                        .content("{\"challengeId\":\"" + challenge + "\",\"channel\":\"" + channel + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value(channel));
        return "SMS".equals(channel) ? lastSmsCode(msisdn) : lastCode(msisdn);
    }

    private String lastCode(String msisdn) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(whatsApp, atLeastOnce()).sendCustomNotification(eq(msisdn), text.capture());
        return code(text.getAllValues());
    }

    private String lastSmsCode(String msisdn) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sms, atLeastOnce()).sendSms(eq(msisdn), text.capture(), anyString());
        return code(text.getAllValues());
    }

    private static String code(List<String> messages) {
        Pattern p = Pattern.compile("InnBucks code:? (\\d{6})");
        for (int i = messages.size() - 1; i >= 0; i--) {
            Matcher m = p.matcher(messages.get(i));
            if (m.find()) return m.group(1);
        }
        throw new AssertionError("no OTP was sent: " + messages);
    }

    private String verifyOtp(String challenge, String code, String install) throws Exception {
        return mvc.perform(verifyRequest(challenge, code, install))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MockHttpServletRequestBuilder verifyRequest(String challenge, String code, String install) {
        return broker(post("/auth/client-service/otp/verify"), install)
                .content("{\"challengeId\":\"" + challenge + "\",\"otp\":\"" + code + "\"}");
    }

    private org.springframework.test.web.servlet.ResultActions redeem(String ticket, String msisdn, String install,
                                                                      String purpose) throws Exception {
        return mvc.perform(post("/device-security/broker/tickets/redeem").header("x-api-key", BROKER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":\"" + ticket + "\",\"msisdn\":\"" + msisdn + "\",\"installId\":\"" + install
                                + "\",\"purpose\":\"" + purpose + "\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions loginResult(String ticketId, String outcome) throws Exception {
        return mvc.perform(loginResultRequest(ticketId, outcome)).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder loginResultRequest(String ticketId, String outcome) {
        return post("/device-security/broker/login-result").header("x-api-key", BROKER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketId\":\"" + ticketId + "\",\"outcome\":\"" + outcome + "\",\"stagingCode\":\"000\"}");
    }

    private MockHttpServletRequestBuilder broker(MockHttpServletRequestBuilder b, String install) {
        b.header("x-api-key", BROKER_KEY).header("X-Forwarded-For", ip).contentType(MediaType.APPLICATION_JSON);
        if (install != null) b.header("x-device-id", install);
        return b;
    }

    private DeviceRow row(Verified phone) {
        return jdbc.queryForObject("SELECT state, state_reason FROM customer_devices WHERE public_id = ?",
                (rs, i) -> new DeviceRow(rs.getString("state"), rs.getString("state_reason")), phone.deviceId());
    }

    private DeviceRow row(String msisdn, String install) {
        return jdbc.queryForObject("SELECT state, state_reason FROM customer_devices WHERE msisdn = ? AND install_id_hash = ?",
                (rs, i) -> new DeviceRow(rs.getString("state"), rs.getString("state_reason")), msisdn,
                DeviceIdentity.hashInstallId(install));
    }

    private int trustedRows(String msisdn) {
        return jdbc.queryForObject("SELECT count(*) FROM customer_devices WHERE msisdn = ? AND state = 'TRUSTED'",
                Integer.class, msisdn);
    }

    private Timestamp voidedAt(String jti) {
        return jdbc.queryForObject("SELECT voided_at FROM device_login_tickets WHERE jti = ?", Timestamp.class, jti);
    }

    /** The notes of the number's DEVICE_REVOKED events with reason REPLACED: who signed whom out. */
    private List<String> replacedNotes(String msisdn) {
        return jdbc.queryForList("SELECT note FROM device_security_events WHERE msisdn = ? "
                + "AND event_type = 'DEVICE_REVOKED' AND reason = 'REPLACED' ORDER BY id", String.class, msisdn);
    }

    private static String jti(String jws) {
        String payload = new String(Base64.getUrlDecoder().decode(jws.split("\\.")[1]), StandardCharsets.UTF_8);
        return JsonPath.read(payload, "$.jti");
    }

    /** A fresh valid Econet number per call, so rounds never share a registry row. */
    private static String number() {
        return "+26377" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }
}
