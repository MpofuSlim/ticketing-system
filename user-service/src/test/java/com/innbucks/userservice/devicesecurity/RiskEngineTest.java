package com.innbucks.userservice.devicesecurity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract's §8 rules as the pure function {@link RiskEngine} implements
 * them. Each case builds the facts one rule needs and nothing else, so a failing
 * case names the rule that moved.
 */
class RiskEngineTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 9, 58, 12);
    private final RiskEngine engine = new RiskEngine();

    /** A mutable builder for the engine's input, defaulting to "a trusted phone signing in, nothing odd". */
    static final class In {
        SignInPurpose purpose = SignInPurpose.SIGN_IN;
        SignInContext context = SignInContext.SIGN_IN;
        DeviceState state = DeviceState.TRUSTED;
        String stateReason;
        LocalDateTime trustedUntil = NOW.plusDays(30);
        boolean boundBefore = true;
        boolean inPinGrace;
        String storedPlatform = "android", storedManufacturer = "samsung", storedModel = "SM-A155F";
        String platform = "android", manufacturer = "samsung", model = "SM-A155F";
        Set<IntegrityThreat> threats = EnumSet.noneOf(IntegrityThreat.class);
        String integritySource = "freerasp";
        String appCheck = "valid";
        String locationStatus = "GRANTED";
        Double lat = -17.825, lng = 31.053;
        Boolean mocked = false;
        boolean fraudFlagged;
        LocalDateTime pinIssuedAt;
        LocalDateTime lastSignInAt = NOW.minusDays(1);
        RiskEngine.LastFix lastFix;
        List<double[]> knownFixes = new ArrayList<>();
        long otherTrusted;
        long otherBoundCustomers;
        int numbersLastHour = 1;
        long wrongPins;
        boolean production = true;
        DeviceSecurityProperties.Risk risk = new DeviceSecurityProperties.Risk();

        RiskEngine.Input build() {
            return new RiskEngine.Input(NOW, purpose, context, state, stateReason, trustedUntil, boundBefore, inPinGrace,
                    storedPlatform, storedManufacturer, storedModel, platform, manufacturer, model, threats,
                    integritySource, appCheck, locationStatus, lat, lng, mocked, fraudFlagged, pinIssuedAt,
                    lastSignInAt, lastFix, knownFixes, otherTrusted, otherBoundCustomers, numbersLastHour, wrongPins,
                    production, new DeviceSecurityProperties.Trust(), risk);
        }
    }

    private RiskEngine.Assessment assess(In in) {
        return engine.assess(in.build());
    }

    @Test
    @DisplayName("a trusted phone signing in from a normal place gets TOKEN — a trusted device never gets an OTP just for signing in (§2)")
    void trustedPhone_allowed() {
        RiskEngine.Assessment a = assess(new In());
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
        assertThat(a.decision()).isEqualTo(Decision.TOKEN);
    }

    @Test
    void newDevice_needsOtp() {
        In in = new In();
        in.state = DeviceState.NEW;
        in.boundBefore = false;
        RiskEngine.Assessment a = assess(in);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
        assertThat(a.otpReason()).isEqualTo(OtpReason.NEW_DEVICE);
    }

    @Test
    @DisplayName("a removed phone signing in again starts at NEW (§4)")
    void revokedDevice_isTreatedAsNew() {
        In in = new In();
        in.state = DeviceState.REVOKED;
        assertThat(assess(in).otpReason()).isEqualTo(OtpReason.NEW_DEVICE);
    }

    @Test
    void trustExpired_needsOtp() {
        In in = new In();
        in.trustedUntil = NOW.minusMinutes(1);
        RiskEngine.Assessment a = assess(in);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
        assertThat(a.otpReason()).isEqualTo(OtpReason.TRUST_EXPIRED);
    }

    @Test
    @DisplayName("a Samsung yesterday and an iPhone today under one installId is not the same device (§6.1)")
    void modelChangedUnderSameInstallId_needsOtp() {
        In in = new In();
        in.platform = "ios";
        in.manufacturer = "Apple";
        in.model = "iPhone 15";
        RiskEngine.Assessment a = assess(in);
        assertThat(a.otpReason()).isEqualTo(OtpReason.DEVICE_CHANGED);
        assertThat(a.signals()).containsKey("DEVICE_CHANGED");
    }

    @Test
    @DisplayName("hooks / tampered app / automation / malware ban the device, even on a silent renewal (§8.5)")
    void banningThreats_ban_evenOnRenew() {
        for (IntegrityThreat t : IntegrityThreat.BANNING) {
            In in = new In();
            in.context = SignInContext.RENEW;
            in.threats = EnumSet.of(t);
            RiskEngine.Assessment a = assess(in);
            assertThat(a.verdict()).as(t.name()).isEqualTo(RiskEngine.Verdict.BAN);
            assertThat(a.banReason()).isEqualTo(BanReason.INTEGRITY);
        }
    }

    @Test
    @DisplayName("a waived finding (DEVICE_SECURITY_WAIVED_THREATS=TAMPER) is logged, not banned — the rest of the engine still runs")
    void waivedTamper_isRecordedButDoesNotBan() {
        In in = new In();
        in.threats = EnumSet.of(IntegrityThreat.TAMPER);
        in.risk.setWaivedThreats(EnumSet.of(IntegrityThreat.TAMPER));
        RiskEngine.Assessment a = assess(in);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
        assertThat(a.signals()).containsEntry("INTEGRITY_TAMPER_WAIVED", 0).doesNotContainKey("INTEGRITY_TAMPER");
    }

    @Test
    @DisplayName("waiving TAMPER never waives the others: hooks / automation / malware still ban alongside it")
    void waivingTamper_stillBansTheOthers() {
        for (IntegrityThreat t : IntegrityThreat.BANNING) {
            if (t == IntegrityThreat.TAMPER) continue;
            In in = new In();
            in.threats = EnumSet.of(IntegrityThreat.TAMPER, t);
            in.risk.setWaivedThreats(EnumSet.of(IntegrityThreat.TAMPER));
            RiskEngine.Assessment a = assess(in);
            assertThat(a.verdict()).as(t.name()).isEqualTo(RiskEngine.Verdict.BAN);
            assertThat(a.signals()).containsEntry("INTEGRITY_" + t.name(), 100);
        }
    }

    @Test
    @DisplayName("a waived TAMPER on a NEW phone still gets the new-device code — the waiver lifts the ban, not the other checks")
    void waivedTamper_newPhone_stillStepsUp() {
        In in = new In();
        in.state = DeviceState.NEW;
        in.boundBefore = false;
        in.trustedUntil = null;
        in.threats = EnumSet.of(IntegrityThreat.TAMPER);
        in.risk.setWaivedThreats(EnumSet.of(IntegrityThreat.TAMPER));
        RiskEngine.Assessment a = assess(in);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
        assertThat(a.otpReason()).isEqualTo(OtpReason.NEW_DEVICE);
    }

    @Test
    @DisplayName("root / jailbreak is an INTEGRITY_HOLD with no timer (§8.4)")
    void root_isIntegrityHold() {
        In in = new In();
        in.threats = EnumSet.of(IntegrityThreat.ROOT);
        RiskEngine.Assessment a = assess(in);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.INTEGRITY_HOLD);
        assertThat(a.decision()).isEqualTo(Decision.TEMP_BLOCKED);
        assertThat(a.reasonCode()).isEqualTo("INTEGRITY_HOLD");
    }

    @Test
    @DisplayName("a third customer on a phone already bound to two is the mule-farm ban (§8.5)")
    void thirdCustomerOnDevice_isSharedDeviceBan() {
        In in = new In();
        in.state = DeviceState.NEW;
        in.boundBefore = false;
        in.otherBoundCustomers = 2;
        RiskEngine.Assessment a = assess(in);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.BAN);
        assertThat(a.banReason()).isEqualTo(BanReason.SHARED_DEVICE);
        assertThat(a.banReason().deviceWide()).isTrue();
    }

    @Test
    @DisplayName("a family phone (a second customer) is allowed (§17)")
    void secondCustomerOnDevice_isOnlyAnOtp() {
        In in = new In();
        in.state = DeviceState.NEW;
        in.boundBefore = false;
        in.otherBoundCustomers = 1;
        assertThat(assess(in).verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
    }

    @Test
    @DisplayName("more than 3 numbers from one phone in an hour is a temporary block (§8.4)")
    void manyNumbersFromOneDevice_blocks() {
        In in = new In();
        in.numbersLastHour = 4;
        RiskEngine.Assessment a = assess(in);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.TEMP_BLOCK);
        assertThat(a.blockReason()).isEqualTo(BlockReason.VELOCITY);
    }

    @Test
    @DisplayName("RENEW from a trusted phone ignores the soft triggers (§6.1)")
    void renew_ignoresSoftTriggers() {
        In in = new In();
        in.context = SignInContext.RENEW;
        in.mocked = true;
        in.appCheck = "absent";
        in.lastSignInAt = NOW.minusDays(60);
        assertThat(assess(in).verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
    }

    @Test
    void renew_withEmulator_needsOtp() {
        In in = new In();
        in.context = SignInContext.RENEW;
        in.threats = EnumSet.of(IntegrityThreat.EMULATOR);
        assertThat(assess(in).verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
    }

    @Test
    @DisplayName("the number check never asks for an OTP (§5.7)")
    void lookup_neverOtp() {
        In in = new In();
        in.purpose = SignInPurpose.LOOKUP;
        in.state = DeviceState.NEW;
        in.boundBefore = false;
        in.mocked = true;
        assertThat(assess(in).verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
    }

    @Test
    @DisplayName("PIN issue always asks for an OTP, even on a trusted phone (§5.7)")
    void pinIssue_alwaysOtp() {
        In in = new In();
        in.purpose = SignInPurpose.PIN_ISSUE;
        RiskEngine.Assessment a = assess(in);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
        assertThat(a.otpReason()).isEqualTo(OtpReason.PIN_ISSUE);
    }

    @Test
    @DisplayName("inside the PIN-retry grace, a fresh OTP was just proven: no second code")
    void pinGrace_skipsSoftTriggers() {
        In in = new In();
        in.state = DeviceState.PENDING_PIN;
        in.inPinGrace = true;
        in.purpose = SignInPurpose.PIN_ISSUE;
        assertThat(assess(in).verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
    }

    @Test
    void justUnlocked_asksWithReasonUnlocked() {
        In in = new In();
        in.state = DeviceState.STEP_UP;
        in.stateReason = OtpReason.UNLOCKED.name();
        assertThat(assess(in).otpReason()).isEqualTo(OtpReason.UNLOCKED);
    }

    @Test
    @DisplayName("Harare then Mutare-to-London in ten minutes is impossible travel (§8.3)")
    void impossibleTravel_needsOtp() {
        In in = new In();
        in.lastFix = new RiskEngine.LastFix(51.507, -0.128, NOW.minusMinutes(10));
        RiskEngine.Assessment a = assess(in);
        assertThat(a.signals()).containsKey("IMPOSSIBLE_TRAVEL");
        assertThat(a.otpReason()).isEqualTo(OtpReason.LOCATION_ANOMALY);
    }

    @Test
    void harareToBulawayoOvernight_isFine() {
        In in = new In();
        in.lastFix = new RiskEngine.LastFix(-20.150, 28.583, NOW.minusHours(10));
        assertThat(assess(in).signals()).doesNotContainKey("IMPOSSIBLE_TRAVEL");
    }

    @Test
    @DisplayName("outside every known place once the customer has a pattern; silent before they do (§8.1)")
    void outsideKnownPlaces() {
        In in = notYetTrusted();
        for (int i = 0; i < 6; i++) in.knownFixes.add(new double[]{-20.150, 28.583}); // Bulawayo, six times
        RiskEngine.Assessment a = assess(in);
        assertThat(a.signals()).containsKey("OUTSIDE_KNOWN_PLACES");
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);

        // A trusted phone records it but is not asked: a new place is not a new phone.
        In trusted = new In();
        for (int i = 0; i < 6; i++) trusted.knownFixes.add(new double[]{-20.150, 28.583});
        RiskEngine.Assessment t = assess(trusted);
        assertThat(t.signals()).containsKey("OUTSIDE_KNOWN_PLACES");
        assertThat(t.verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);

        In young = new In();
        for (int i = 0; i < 3; i++) young.knownFixes.add(new double[]{-20.150, 28.583});
        assertThat(assess(young).signals()).doesNotContainKey("OUTSIDE_KNOWN_PLACES");

        In home = new In();
        for (int i = 0; i < 6; i++) home.knownFixes.add(new double[]{-17.829, 31.052});
        assertThat(assess(home).verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
    }

    @Test
    @DisplayName("refusing location is a risk input, never a lockout (§3 rule 8)")
    void locationDenied_scoresButNeverAsks() {
        In in = new In();
        in.locationStatus = "DENIED";
        in.lat = null;
        in.lng = null;
        RiskEngine.Assessment a = assess(in);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
        assertThat(a.signals()).containsKey("LOCATION_REFUSED");
    }

    @Test
    void mockedLocation_needsOtp() {
        In in = new In();
        in.mocked = true;
        assertThat(assess(in).signals()).containsKey("MOCKED_LOCATION");
        assertThat(assess(in).verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
    }

    @Test
    @DisplayName("integrity 'unavailable' is a risk signal on production only")
    void integrityUnavailable_onlyOnProduction() {
        In prod = notYetTrusted();
        prod.integritySource = "unavailable";
        assertThat(assess(prod).signals()).containsKey("INTEGRITY_UNAVAILABLE");
        assertThat(assess(prod).verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);

        In trusted = new In();
        trusted.integritySource = "unavailable";
        assertThat(assess(trusted).signals()).containsKey("INTEGRITY_UNAVAILABLE");
        assertThat(assess(trusted).verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);

        In staging = notYetTrusted();
        staging.integritySource = "unavailable";
        staging.production = false;
        assertThat(assess(staging).signals()).doesNotContainKey("INTEGRITY_UNAVAILABLE");
    }

    @Test
    void dormantAccount_needsOtp() {
        In in = new In();
        in.lastSignInAt = NOW.minusDays(31);
        assertThat(assess(in).signals()).containsKey("DORMANT");
        assertThat(assess(in).verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
    }

    @Test
    void recentPinIssue_andWrongPins_needOtp() {
        In pin = new In();
        pin.pinIssuedAt = NOW.minusDays(2);
        assertThat(assess(pin).signals()).containsKey("PIN_RECENTLY_ISSUED");

        In pins = new In();
        pins.wrongPins = 3;
        assertThat(assess(pins).signals()).containsKey("WRONG_PINS");
        assertThat(assess(pins).verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
    }

    @Test
    void moreThanThreeTrustedDevices_needsOtp() {
        In in = notYetTrusted();
        in.otherTrusted = 3;
        assertThat(assess(in).signals()).containsKey("TOO_MANY_DEVICES");
        assertThat(assess(in).verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);

        // The fourth phone is asked ONCE, when it joins; once trusted it is not asked every time.
        In trusted = new In();
        trusted.otherTrusted = 3;
        assertThat(assess(trusted).signals()).containsKey("TOO_MANY_DEVICES");
        assertThat(assess(trusted).verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
    }

    @Test
    @DisplayName("a trusted phone is NOT asked again for standing conditions — the production login loop (2026-09-30)")
    void trustedPhone_standingConditions_neverAskAgain() {
        // Exactly what the broker sends today: app check "absent" on every request.
        In absent = new In();
        absent.appCheck = "absent";
        RiskEngine.Assessment a = assess(absent);
        assertThat(a.signals()).containsKey("APP_CHECK_ABSENT");
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
        assertThat(a.decision()).isEqualTo(Decision.TOKEN);

        // All of them at once still does not ask, and does not add up to a block.
        In all = new In();
        all.appCheck = "absent";
        all.integritySource = "unavailable";
        all.pinIssuedAt = NOW.minusDays(2);
        all.otherTrusted = 3;
        all.locationStatus = "DENIED";
        all.lat = null;
        all.lng = null;
        RiskEngine.Assessment b = assess(all);
        assertThat(b.signals()).containsKeys("APP_CHECK_ABSENT", "INTEGRITY_UNAVAILABLE", "PIN_RECENTLY_ISSUED",
                "TOO_MANY_DEVICES", "LOCATION_REFUSED");
        assertThat(b.verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
        assertThat(b.score()).isZero();
    }

    @Test
    @DisplayName("a phone not yet trusted IS asked for a standing condition (that is the one time it counts)")
    void untrustedPhone_appCheckAbsent_asks() {
        In in = notYetTrusted();
        in.appCheck = "absent";
        assertThat(assess(in).verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
    }

    @Test
    @DisplayName("a trusted phone is still asked on a real CHANGE, and a standing condition cannot tip it into a block")
    void trustedPhone_realChange_stillAsks_butNotBlocked() {
        In changed = new In();
        changed.appCheck = "absent";
        changed.model = "SM-S918B";            // a different handset
        assertThat(assess(changed).verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
        assertThat(assess(changed).otpReason()).isEqualTo(OtpReason.DEVICE_CHANGED);

        // Impossible travel (40) + integrity/app-check/new-place (55) would have been 95 ≥ 90:
        // a blocked customer. The standing part no longer counts toward the block.
        In traveller = new In();
        traveller.appCheck = "absent";
        traveller.integritySource = "unavailable";
        traveller.lastFix = new RiskEngine.LastFix(-33.925, 18.424, NOW.minusHours(1)); // Cape Town an hour ago
        for (int i = 0; i < 6; i++) traveller.knownFixes.add(new double[]{-33.925, 18.424});
        RiskEngine.Assessment t = assess(traveller);
        assertThat(t.signals()).containsKey("IMPOSSIBLE_TRAVEL");
        assertThat(t.verdict()).isEqualTo(RiskEngine.Verdict.STEP_UP);
        assertThat(t.otpReason()).isEqualTo(OtpReason.LOCATION_ANOMALY);
    }

    /** A phone whose trust window has ended: every soft trigger counts again, as it did before trust. */
    private static In notYetTrusted() {
        In in = new In();
        in.trustedUntil = NOW.minusDays(1);
        return in;
    }

    @Test
    @DisplayName("a high enough combined score is a temporary block, not an OTP (§8.4)")
    void highScore_blocks() {
        In in = new In();
        in.state = DeviceState.NEW;
        in.boundBefore = false;
        in.mocked = true;                       // 35
        in.threats = EnumSet.of(IntegrityThreat.EMULATOR); // 30
        in.appCheck = "absent";                  // 20, + NEW_DEVICE 20 = 105
        RiskEngine.Assessment a = assess(in);
        assertThat(a.score()).isGreaterThanOrEqualTo(90);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.TEMP_BLOCK);
        assertThat(a.blockReason()).isEqualTo(BlockReason.RISK);
    }

    @Test
    void fraudFlag_scoresOnly() {
        In in = new In();
        in.fraudFlagged = true;
        RiskEngine.Assessment a = assess(in);
        assertThat(a.verdict()).isEqualTo(RiskEngine.Verdict.ALLOW);
        assertThat(a.score()).isEqualTo(RiskEngine.W_FRAUD_FLAG);
    }
}
