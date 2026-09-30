package com.innbucks.userservice.devicesecurity;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The rules of contract §8, as a pure function: facts in, verdict out. No I/O,
 * no clock, no state — everything it needs arrives in {@link Input}, so every
 * rule can be pinned by a unit test and every decision reconstructed from the
 * features it logs.
 *
 * <p>Three tiers, strongest first:
 * <ol>
 *   <li><b>Hard triggers</b> apply in every context, renewals included: a
 *       banning integrity finding, the mule-farm pattern, a root/jailbreak hold,
 *       too many numbers from one phone.</li>
 *   <li><b>Soft triggers</b> (§8.3) ask for an OTP. They are skipped for a
 *       silent renewal from a device inside its trust window (§6.1: a trusted
 *       phone renews many times a day and must not be asked for codes), and
 *       while a device is inside its PIN-retry grace (it proved possession
 *       minutes ago).</li>
 *   <li><b>The score</b> sums the soft signals. At or above
 *       {@code risk.block-score} the OTP becomes a temporary block instead —
 *       "impossible travel plus a new device plus a mocked location" is not
 *       something an OTP should be allowed to paper over.</li>
 * </ol>
 *
 * <p>OTP velocity ceilings are deliberately NOT here: whether a new challenge is
 * needed at all depends on whether a live one can be reused, which only the OTP
 * service knows.
 */
@Component
public class RiskEngine {

    /** What the engine concluded. */
    public enum Verdict { ALLOW, STEP_UP, TEMP_BLOCK, INTEGRITY_HOLD, BAN }

    /** Everything the rules read. Built by the sign-in service from the request and the registry. */
    public record Input(
            LocalDateTime now,
            SignInPurpose purpose,
            SignInContext context,
            // the pair
            DeviceState state,
            String stateReason,
            LocalDateTime trustedUntil,
            boolean boundBefore,
            boolean inPinGrace,
            String storedPlatform,
            String storedManufacturer,
            String storedModel,
            // what the app sent
            String platform,
            String manufacturer,
            String model,
            Set<IntegrityThreat> threats,
            String integritySource,
            String appCheck,
            String locationStatus,
            Double lat,
            Double lng,
            Boolean mocked,
            // the customer
            boolean fraudFlagged,
            LocalDateTime pinIssuedAt,
            LocalDateTime lastSignInAt,
            LastFix lastFix,
            List<double[]> knownFixes,
            // counts
            long otherTrustedDevices,
            long otherBoundCustomers,
            int distinctNumbersOnDeviceLastHour,
            long wrongPinsOnDeviceLastDay,
            // policy
            boolean production,
            DeviceSecurityProperties.Trust trust,
            DeviceSecurityProperties.Risk risk) {
    }

    /** The most recent located successful sign-in, for impossible travel. */
    public record LastFix(double lat, double lng, LocalDateTime at) {
    }

    /** What the engine concluded, with the features that led to it (for the decision log). */
    public record Assessment(
            Verdict verdict,
            OtpReason otpReason,
            BlockReason blockReason,
            BanReason banReason,
            int score,
            Map<String, Integer> signals) {

        public Decision decision() {
            return switch (verdict) {
                case ALLOW -> Decision.TOKEN;
                case STEP_UP -> Decision.OTP_REQUIRED;
                case TEMP_BLOCK, INTEGRITY_HOLD -> Decision.TEMP_BLOCKED;
                case BAN -> Decision.BANNED;
            };
        }

        /** The reason string logged for this verdict. */
        public String reasonCode() {
            return switch (verdict) {
                case ALLOW -> null;
                case STEP_UP -> otpReason.name();
                case TEMP_BLOCK -> blockReason.name();
                case INTEGRITY_HOLD -> BlockReason.INTEGRITY_HOLD.name();
                case BAN -> banReason.name();
            };
        }
    }

    // Weights of the soft signals. Internal, logged, never returned (§12).
    static final int W_NEW_DEVICE = 20;
    static final int W_DEVICE_CHANGED = 30;
    static final int W_TRUST_EXPIRED = 10;
    static final int W_IMPOSSIBLE_TRAVEL = 40;
    static final int W_OUTSIDE_KNOWN_PLACES = 20;
    static final int W_MOCKED_LOCATION = 35;
    static final int W_EMULATOR = 30;
    static final int W_DEBUGGER = 25;
    static final int W_INTEGRITY_UNAVAILABLE = 15;
    static final int W_APP_CHECK_ABSENT = 20;
    static final int W_DORMANT = 10;
    static final int W_WRONG_PINS = 25;
    static final int W_PIN_RECENTLY_ISSUED = 10;
    static final int W_TOO_MANY_DEVICES = 10;
    static final int W_FRAUD_FLAG = 20;
    static final int W_LOCATION_REFUSED = 5;

    public Assessment assess(Input in) {
        Map<String, Integer> signals = new LinkedHashMap<>();
        Set<IntegrityThreat> threats = in.threats() == null ? EnumSet.noneOf(IntegrityThreat.class) : in.threats();

        // ---- Tier 1: hard triggers ------------------------------------------------
        for (IntegrityThreat t : IntegrityThreat.BANNING) {
            if (threats.contains(t)) {
                signals.put("INTEGRITY_" + t.name(), 100);
            }
        }
        if (!signals.isEmpty()) {
            return new Assessment(Verdict.BAN, null, null, BanReason.INTEGRITY, 100, signals);
        }

        boolean binds = in.purpose() != SignInPurpose.LOOKUP;
        if (binds && !in.boundBefore() && in.otherBoundCustomers() >= in.trust().getMaxCustomersPerDevice()) {
            signals.put("SHARED_DEVICE", 100);
            return new Assessment(Verdict.BAN, null, null, BanReason.SHARED_DEVICE, 100, signals);
        }

        if (threats.contains(IntegrityThreat.ROOT)) {
            signals.put("INTEGRITY_ROOT", 100);
            return new Assessment(Verdict.INTEGRITY_HOLD, null, BlockReason.INTEGRITY_HOLD, null, 100, signals);
        }

        if (in.context() == SignInContext.SIGN_IN
                && in.distinctNumbersOnDeviceLastHour() > in.risk().getNumbersPerDeviceHourly()) {
            signals.put("MANY_NUMBERS_ON_DEVICE", 100);
            return new Assessment(Verdict.TEMP_BLOCK, null, BlockReason.VELOCITY, null, 100, signals);
        }

        // ---- The number check never asks for an OTP (§5.7) ------------------------
        if (in.purpose() == SignInPurpose.LOOKUP) {
            return new Assessment(Verdict.ALLOW, null, null, null, 0, signals);
        }

        // ---- A trusted phone renewing silently: hard triggers only (§6.1) --------
        boolean trustedWindow = in.state() == DeviceState.TRUSTED
                && in.trustedUntil() != null && in.trustedUntil().isAfter(in.now());
        boolean emulatorOrDebugger = threats.contains(IntegrityThreat.EMULATOR) || threats.contains(IntegrityThreat.DEBUGGER);
        if (in.context() == SignInContext.RENEW && trustedWindow) {
            if (emulatorOrDebugger) {
                signals.put(threats.contains(IntegrityThreat.EMULATOR) ? "EMULATOR" : "DEBUGGER",
                        threats.contains(IntegrityThreat.EMULATOR) ? W_EMULATOR : W_DEBUGGER);
                return new Assessment(Verdict.STEP_UP, OtpReason.RISK, null, null, sum(signals), signals);
            }
            return new Assessment(Verdict.ALLOW, null, null, null, 0, signals);
        }

        // ---- Fresh possession proof: the OTP was verified minutes ago ------------
        if (in.inPinGrace()) {
            return new Assessment(Verdict.ALLOW, null, null, null, 0, signals);
        }

        // ---- Tier 2: soft triggers (§8.3) -----------------------------------------
        List<OtpReason> reasons = new ArrayList<>();

        // A phone inside its trust window has already proved it holds the SIM (with
        // OTP enforced, provisional watch-mode trust reaches the engine as NEW, never
        // as TRUSTED). For it, STANDING conditions — the same on every request from
        // that phone, such as the broker reporting app check "absent", no integrity
        // result, a recently issued PIN, a fourth phone on the account, a place not
        // seen before — are recorded and scored but never ask for a code again: the
        // code the phone already passed answered them, and asking on every sign-in
        // turned each login into an SMS (found in production 2026-09-30:
        // APP_CHECK_ABSENT on every request). What still asks is a CHANGE: a
        // different handset, impossible travel, a mocked location, an emulator or
        // debugger, a burst of wrong PINs, a dormant account, or a PIN (re)issue.
        boolean provenTrusted = trustedWindow;

        if (in.purpose() == SignInPurpose.PIN_ISSUE) {
            reasons.add(OtpReason.PIN_ISSUE);
        }

        switch (in.state()) {
            case NEW, REVOKED -> {
                signals.put("NEW_DEVICE", W_NEW_DEVICE);
                reasons.add(OtpReason.NEW_DEVICE);
            }
            case STEP_UP -> reasons.add(stepUpReason(in.stateReason()));
            case PENDING_PIN -> {
                // Grace has run out without a successful login: prove possession again.
                reasons.add(OtpReason.RISK);
            }
            case TRUSTED -> {
                if (!trustedWindow) {
                    signals.put("TRUST_EXPIRED", W_TRUST_EXPIRED);
                    reasons.add(OtpReason.TRUST_EXPIRED);
                }
            }
            default -> {
                // TEMP_BLOCKED / BANNED never reach the engine: the service answers them first.
            }
        }

        if (in.state() != DeviceState.NEW && in.state() != DeviceState.REVOKED
                && (changed(in.storedPlatform(), in.platform())
                || changed(in.storedManufacturer(), in.manufacturer())
                || changed(in.storedModel(), in.model()))) {
            signals.put("DEVICE_CHANGED", W_DEVICE_CHANGED);
            reasons.add(OtpReason.DEVICE_CHANGED);
        }

        boolean granted = "GRANTED".equalsIgnoreCase(in.locationStatus()) && in.lat() != null && in.lng() != null;
        if (granted) {
            if (Boolean.TRUE.equals(in.mocked())) {
                signals.put("MOCKED_LOCATION", W_MOCKED_LOCATION);
                reasons.add(OtpReason.RISK);
            } else {
                if (impossibleTravel(in)) {
                    signals.put("IMPOSSIBLE_TRAVEL", W_IMPOSSIBLE_TRAVEL);
                    reasons.add(OtpReason.LOCATION_ANOMALY);
                }
                if (outsideKnownPlaces(in)) {
                    signals.put("OUTSIDE_KNOWN_PLACES", W_OUTSIDE_KNOWN_PLACES);
                    if (!provenTrusted) reasons.add(OtpReason.LOCATION_ANOMALY);
                }
            }
        } else if (in.locationStatus() != null && "DENIED".equalsIgnoreCase(in.locationStatus())) {
            // A risk input, never a lockout (§3 rule 8): scores, never asks for an OTP by itself.
            signals.put("LOCATION_REFUSED", W_LOCATION_REFUSED);
        }

        if (threats.contains(IntegrityThreat.EMULATOR)) {
            signals.put("EMULATOR", W_EMULATOR);
            reasons.add(OtpReason.RISK);
        }
        if (threats.contains(IntegrityThreat.DEBUGGER)) {
            signals.put("DEBUGGER", W_DEBUGGER);
            reasons.add(OtpReason.RISK);
        }
        if (in.production() && (in.integritySource() == null
                || "unavailable".equalsIgnoreCase(in.integritySource().trim()))) {
            signals.put("INTEGRITY_UNAVAILABLE", W_INTEGRITY_UNAVAILABLE);
            if (!provenTrusted) reasons.add(OtpReason.RISK);
        }
        if (in.appCheck() != null && "absent".equalsIgnoreCase(in.appCheck().trim())) {
            signals.put("APP_CHECK_ABSENT", W_APP_CHECK_ABSENT);
            if (!provenTrusted) reasons.add(OtpReason.RISK);
        }
        if (in.lastSignInAt() != null
                && in.lastSignInAt().isBefore(in.now().minusDays(in.trust().getDormantDays()))) {
            signals.put("DORMANT", W_DORMANT);
            reasons.add(OtpReason.POLICY);
        }
        if (in.wrongPinsOnDeviceLastDay() >= in.risk().getWrongPinsPerDay()) {
            signals.put("WRONG_PINS", W_WRONG_PINS);
            reasons.add(OtpReason.RISK);
        }
        if (in.pinIssuedAt() != null
                && in.pinIssuedAt().isAfter(in.now().minusDays(in.trust().getPinIssueElevatedDays()))) {
            signals.put("PIN_RECENTLY_ISSUED", W_PIN_RECENTLY_ISSUED);
            if (!provenTrusted) reasons.add(OtpReason.POLICY);
        }
        long trustedIncludingThis = in.otherTrustedDevices() + 1;
        if (trustedIncludingThis > in.trust().getMaxTrustedDevices()) {
            signals.put("TOO_MANY_DEVICES", W_TOO_MANY_DEVICES);
            if (!provenTrusted) reasons.add(OtpReason.POLICY);
        }
        if (in.fraudFlagged()) {
            // Scores only: the fraud flag shortens trust, it does not force a code.
            signals.put("FRAUD_FLAG", W_FRAUD_FLAG);
        }

        // A trusted phone's standing conditions do not add up to a block either: a
        // customer who travels (a real signal) must not be tipped over the block
        // score by the same missing app check that is on every one of their requests.
        int score = sum(signals) - (provenTrusted ? ambient(signals) : 0);
        if (score >= in.risk().getBlockScore()) {
            return new Assessment(Verdict.TEMP_BLOCK, null, BlockReason.RISK, null, score, signals);
        }
        if (!reasons.isEmpty()) {
            return new Assessment(Verdict.STEP_UP, primary(reasons), null, null, score, signals);
        }
        return new Assessment(Verdict.ALLOW, null, null, null, score, signals);
    }

    /** Standing conditions: scored for a trusted phone but never, on their own, a reason to ask again. */
    static final Set<String> AMBIENT = Set.of("APP_CHECK_ABSENT", "INTEGRITY_UNAVAILABLE", "OUTSIDE_KNOWN_PLACES",
            "PIN_RECENTLY_ISSUED", "TOO_MANY_DEVICES", "LOCATION_REFUSED");

    private static int ambient(Map<String, Integer> signals) {
        int total = 0;
        for (Map.Entry<String, Integer> e : signals.entrySet()) {
            if (AMBIENT.contains(e.getKey())) total += e.getValue();
        }
        return total;
    }

    /** The reason the app's log shows when several fired: the one most useful to support. */
    private static OtpReason primary(List<OtpReason> reasons) {
        for (OtpReason r : List.of(OtpReason.PIN_ISSUE, OtpReason.UNLOCKED, OtpReason.NEW_DEVICE,
                OtpReason.DEVICE_CHANGED, OtpReason.LOCATION_ANOMALY, OtpReason.RISK,
                OtpReason.TRUST_EXPIRED, OtpReason.POLICY)) {
            if (reasons.contains(r)) return r;
        }
        return reasons.get(0);
    }

    private static OtpReason stepUpReason(String stored) {
        if (stored == null) return OtpReason.RISK;
        try {
            return OtpReason.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return OtpReason.RISK;
        }
    }

    private static boolean changed(String stored, String presented) {
        if (stored == null || stored.isBlank() || presented == null || presented.isBlank()) {
            return false;
        }
        return !stored.trim().toLowerCase(Locale.ROOT).equals(presented.trim().toLowerCase(Locale.ROOT));
    }

    /** Two sign-ins further apart than any flight could manage in the time between them (§8.3). */
    private static boolean impossibleTravel(Input in) {
        LastFix last = in.lastFix();
        if (last == null || last.at() == null) return false;
        double km = GeoMath.km(last.lat(), last.lng(), in.lat(), in.lng());
        if (km < in.risk().getImpossibleTravelMinKm()) return false;
        double hours = Math.max(Duration.between(last.at(), in.now()).toSeconds(), 60) / 3600.0;
        return km / hours > in.risk().getImpossibleTravelKmh();
    }

    /**
     * Outside every learned centre (§8.1). A point is a known place once the
     * customer has signed in successfully {@code place-min-visits} times within
     * {@code place-radius-km} of it; with fewer than {@code place-min-history}
     * located sign-ins there is not yet a pattern to be outside of.
     */
    private static boolean outsideKnownPlaces(Input in) {
        List<double[]> fixes = in.knownFixes();
        if (fixes == null || fixes.size() < in.risk().getPlaceMinHistory()) return false;
        int near = 0;
        for (double[] f : fixes) {
            if (GeoMath.km(f[0], f[1], in.lat(), in.lng()) <= in.risk().getPlaceRadiusKm()) {
                near++;
                if (near >= in.risk().getPlaceMinVisits()) return false;
            }
        }
        return true;
    }

    private static int sum(Map<String, Integer> signals) {
        int s = 0;
        for (int v : signals.values()) s += v;
        return s;
    }
}
