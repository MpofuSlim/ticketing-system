package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpSendResponse;
import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import com.innbucks.userservice.devicesecurity.entity.DeviceOtpChallenge;
import com.innbucks.userservice.devicesecurity.repository.DeviceOtpChallengeRepository;
import com.innbucks.userservice.security.OtpHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTP challenges for device security (contract §5.2, §5.3, §11 — all FIRM).
 *
 * <ul>
 *   <li>6 digits from {@link SecureRandom}; stored only as an HMAC under
 *       {@code otp.hmac-secret} ({@link OtpHasher}) and compared in constant
 *       time.</li>
 *   <li>5 minutes, 3 attempts per challenge, resend after 60 seconds at most
 *       twice, single use.</li>
 *   <li>Ceilings per number (5/hour, 20/day), per device and per network
 *       address, counted in CHALLENGES — a live challenge is reused rather than
 *       re-created, so a retried request costs nothing.</li>
 *   <li><b>No code leaves before the app picks a channel.</b> A challenge is
 *       created with the OTP_REQUIRED decision, but the code is minted and sent
 *       only on {@code /otp/send}.</li>
 *   <li><b>A send never holds a database connection across the network
 *       call.</b> The new code is committed first, delivered second, and put back
 *       if delivery fails — so a hung gateway cannot drain the connection pool
 *       that every sign-in shares.</li>
 * </ul>
 */
@Service
@Slf4j
public class DeviceOtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A ceiling was hit when a NEW challenge was needed. */
    public static class OtpCeilingException extends RuntimeException {
        public enum Scope { NUMBER, DEVICE, IP }

        private final Scope scope;
        private final long retryAfterSeconds;

        public OtpCeilingException(Scope scope, long retryAfterSeconds) {
            super("OTP ceiling reached: " + scope);
            this.scope = scope;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public Scope scope() {
            return scope;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** A challenge to answer OTP_REQUIRED with. */
    public record Opened(DeviceOtpChallenge challenge, boolean reused) {
    }

    public enum VerifyStatus { VERIFIED, WRONG, DEAD, GONE, NOT_SENT }

    public record VerifyOutcome(VerifyStatus status, DeviceOtpChallenge challenge) {
    }

    private final DeviceOtpChallengeRepository challenges;
    private final OtpHasher otpHasher;
    private final DeviceSecurityNotifier notifier;
    private final DeviceSecurityMessages messages;
    private final DeviceSecurityEventLog events;
    private final DeviceSecurityMetrics metrics;
    private final DeviceSecurityProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Map<OtpChannel, LocalDateTime> downUntil = new ConcurrentHashMap<>();

    public DeviceOtpService(DeviceOtpChallengeRepository challenges, OtpHasher otpHasher,
                            DeviceSecurityNotifier notifier, DeviceSecurityMessages messages,
                            DeviceSecurityEventLog events, DeviceSecurityMetrics metrics,
                            DeviceSecurityProperties properties, Clock deviceSecurityClock,
                            PlatformTransactionManager transactionManager) {
        this.challenges = challenges;
        this.otpHasher = otpHasher;
        this.notifier = notifier;
        this.messages = messages;
        this.events = events;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = deviceSecurityClock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ---- open --------------------------------------------------------------------

    /**
     * Returns the device's live challenge for this purpose, or creates one if the
     * ceilings allow. Joins the caller's transaction.
     *
     * @throws OtpCeilingException when a new challenge is needed and a ceiling is reached. Thrown before
     *         any write and excluded from rollback, so the caller can still commit the block it places
     *         in answer (a RuntimeException leaving a joined transactional method would otherwise mark
     *         the caller's transaction rollback-only and fail its commit).
     */
    @Transactional(noRollbackFor = OtpCeilingException.class)
    public Opened open(CustomerDevice device, SignInPurpose purpose, String reason, String requestIp,
                       String requestId, String sessionAction, ActorType actor) {
        LocalDateTime now = now();
        Optional<DeviceOtpChallenge> live = challenges
                .findFirstByCustomerDeviceIdAndPurposeAndStatusOrderByCreatedAtDesc(device.getId(), purpose, ChallengeStatus.OPEN)
                .filter(c -> c.liveAt(now));
        if (live.isPresent()) {
            return new Opened(live.get(), true);
        }
        enforceCeilings(device, requestIp, now);

        DeviceSecurityProperties.Otp otp = properties.getOtp();
        DeviceOtpChallenge challenge = DeviceOtpChallenge.builder()
                .id(LoginTicketSigner.newId("chl_"))
                .msisdn(device.getMsisdn())
                .customerDeviceId(device.getId())
                .installIdHash(device.getInstallIdHash())
                .purpose(purpose)
                .reason(reason)
                .status(ChallengeStatus.OPEN)
                .attemptsLeft(otp.getAttempts())
                .resendsLeft(otp.getMaxResends())
                .sendCount(0)
                .sessionAction(sessionAction)
                .requestIp(DeviceSecurityEventLog.truncate(requestIp, 64))
                .requestId(DeviceSecurityEventLog.truncate(requestId, 64))
                .createdAt(now)
                .expiresAt(now.plus(otp.getChallengeTtl()))
                .build();
        challenges.save(challenge);
        events.record(SecurityEventType.OTP_CHALLENGE_CREATED, actor, null, device.getMsisdn(), device,
                b -> b.reason(reason).purpose(purpose.name()).requestId(requestId).ipAddress(requestIp));
        metrics.otp("created", null);
        return new Opened(challenge, false);
    }

    private void enforceCeilings(CustomerDevice device, String requestIp, LocalDateTime now) {
        DeviceSecurityProperties.Otp otp = properties.getOtp();
        LocalDateTime hourAgo = now.minusHours(1);
        LocalDateTime dayAgo = now.minusDays(1);
        if (requestIp != null && challenges.countByRequestIpAndCreatedAtAfter(requestIp, hourAgo) >= otp.getPerIpHourly()) {
            metrics.otp("ceiling_ip", null);
            throw new OtpCeilingException(OtpCeilingException.Scope.IP, 3600);
        }
        if (challenges.countByMsisdnAndCreatedAtAfter(device.getMsisdn(), hourAgo) >= otp.getPerNumberHourly()
                || challenges.countByMsisdnAndCreatedAtAfter(device.getMsisdn(), dayAgo) >= otp.getPerNumberDaily()) {
            metrics.otp("ceiling_number", null);
            throw new OtpCeilingException(OtpCeilingException.Scope.NUMBER, 3600);
        }
        if (challenges.countByInstallIdHashAndCreatedAtAfter(device.getInstallIdHash(), hourAgo) >= otp.getPerDeviceHourly()
                || challenges.countByInstallIdHashAndCreatedAtAfter(device.getInstallIdHash(), dayAgo) >= otp.getPerDeviceDaily()) {
            metrics.otp("ceiling_device", null);
            throw new OtpCeilingException(OtpCeilingException.Scope.DEVICE, 3600);
        }
    }

    // ---- channels ------------------------------------------------------------------

    /**
     * What works for this number right now (§7.3): SMS only for domestic numbers,
     * and a channel whose provider just failed is left out for a couple of
     * minutes. If that would leave nothing, both are offered — better a try than
     * a dead end.
     */
    public List<OtpChannel> channels(String msisdn) {
        List<OtpChannel> all = new ArrayList<>();
        all.add(OtpChannel.WHATSAPP);
        if (notifier.smsReaches(msisdn)) all.add(OtpChannel.SMS);
        LocalDateTime now = now();
        List<OtpChannel> up = new ArrayList<>(all);
        up.removeIf(c -> {
            LocalDateTime until = downUntil.get(c);
            return until != null && until.isAfter(now);
        });
        return up.isEmpty() ? all : up;
    }

    /** The customer's last choice when it is available, else the configured default, else the first available. */
    public OtpChannel defaultChannel(OtpChannel preferred, List<OtpChannel> available) {
        if (preferred != null && available.contains(preferred)) return preferred;
        OtpChannel configured = properties.getOtp().getDefaultChannel();
        if (configured != null && available.contains(configured)) return configured;
        return available.get(0);
    }

    // ---- send ----------------------------------------------------------------------

    private record Prepared(DeviceOtpChallenge saved, String code, boolean testNumber,
                            Map<String, Object> before) {
    }

    /**
     * Mints a code, commits it, sends it, and puts the previous state back if the
     * provider fails (then 503 naming the channel, so the app offers the other).
     */
    public OtpSendResponse send(String challengeId, OtpChannel channel, String presentedInstallHash,
                                Set<SignInPurpose> purposes, ActorType actor) {
        Prepared p = tx.execute(status -> prepareSend(challengeId, channel, presentedInstallHash, purposes));
        DeviceOtpChallenge c = p.saved();
        if (p.testNumber()) {
            log.info("Device OTP for allow-listed test number {} not sent (fixed code)", DeviceIdentity.logMask(c.getMsisdn()));
        } else {
            try {
                notifier.sendOtp(c.getMsisdn(), channel,
                        messages.otp(p.code(), properties.getOtp().getCodeTtl().toMinutes(), channel),
                        "DTX-OTP-" + c.getId());
            } catch (NotificationDeliveryException e) {
                downUntil.put(channel, now().plus(properties.getOtp().getChannelDownFor()));
                tx.executeWithoutResult(status -> revertSend(c.getId(), p.before(), channel, actor));
                metrics.otp("send_failed", channel);
                log.warn("Device OTP via {} failed for {}: {}", channel, DeviceIdentity.logMask(c.getMsisdn()), e.getMessage());
                List<OtpChannel> others = new ArrayList<>(channels(c.getMsisdn()));
                others.remove(channel);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("challengeId", c.getId());
                extra.put("channel", channel);
                extra.put("alternatives", others);
                throw new DeviceSecurityException(HttpStatus.SERVICE_UNAVAILABLE, "channel_unavailable",
                        channel == OtpChannel.WHATSAPP
                                ? "We couldn't send a WhatsApp message right now. Please try SMS instead."
                                : "We couldn't send an SMS right now. Please try WhatsApp instead.",
                        extra, null);
            }
        }
        downUntil.remove(channel);
        tx.executeWithoutResult(status -> events.record(SecurityEventType.OTP_SENT, actor, null, c.getMsisdn(), null,
                b -> b.installIdHash(c.getInstallIdHash()).channel(channel.name()).purpose(c.getPurpose().name())
                        .reason(c.getReason())));
        metrics.otp("sent", channel);
        return view(c);
    }

    private Prepared prepareSend(String challengeId, OtpChannel channel, String presentedInstallHash,
                                 Set<SignInPurpose> purposes) {
        LocalDateTime now = now();
        DeviceOtpChallenge c = live(challengeId, presentedInstallHash, purposes, now);
        if (channel == OtpChannel.SMS && !notifier.smsReaches(c.getMsisdn())) {
            throw new DeviceSecurityException(HttpStatus.BAD_REQUEST, "channel_not_available",
                    "SMS isn't available for this number. Please use WhatsApp.",
                    Map.of("field", "channel", "challengeId", c.getId()), null);
        }
        if (c.getSentAt() != null) {
            if (c.getResendAfter() != null && now.isBefore(c.getResendAfter())) {
                long wait = Math.max(1, Duration.between(now, c.getResendAfter()).toSeconds());
                throw tooSoon(c, wait, "Please wait " + wait + " seconds before asking for a new code.");
            }
            if (c.getResendsLeft() <= 0) {
                long wait = Math.max(1, Duration.between(now, c.getExpiresAt()).toSeconds());
                throw tooSoon(c, wait, "You've asked for the most codes we can send for this sign-in. "
                        + "Enter the last code we sent, or start again after it expires.");
            }
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("codeHash", c.getCodeHash());
        before.put("channel", c.getChannel());
        before.put("sentAt", c.getSentAt());
        before.put("resendAfter", c.getResendAfter());
        before.put("expiresAt", c.getExpiresAt());
        before.put("resendsLeft", c.getResendsLeft());
        before.put("sendCount", c.getSendCount());

        boolean testNumber = isTestNumber(c.getMsisdn());
        String code = testNumber ? properties.getTestOtp().getCode() : String.format("%06d", RANDOM.nextInt(1_000_000));
        if (c.getSentAt() != null) {
            c.setResendsLeft(c.getResendsLeft() - 1);
        }
        c.setCodeHash(otpHasher.hash(code));
        c.setChannel(channel);
        c.setSentAt(now);
        c.setResendAfter(now.plus(properties.getOtp().getResendAfter()));
        c.setExpiresAt(now.plus(properties.getOtp().getCodeTtl()));
        c.setSendCount(c.getSendCount() + 1);
        DeviceOtpChallenge saved = challenges.saveAndFlush(c);
        return new Prepared(saved, code, testNumber, before);
    }

    private void revertSend(String challengeId, Map<String, Object> before, OtpChannel channel, ActorType actor) {
        challenges.findById(challengeId).ifPresent(c -> {
            c.setCodeHash((String) before.get("codeHash"));
            c.setChannel((OtpChannel) before.get("channel"));
            c.setSentAt((LocalDateTime) before.get("sentAt"));
            c.setResendAfter((LocalDateTime) before.get("resendAfter"));
            c.setExpiresAt((LocalDateTime) before.get("expiresAt"));
            c.setResendsLeft((Integer) before.get("resendsLeft"));
            c.setSendCount((Integer) before.get("sendCount"));
            challenges.save(c);
            events.record(SecurityEventType.OTP_SEND_FAILED, actor, null, c.getMsisdn(), null,
                    b -> b.installIdHash(c.getInstallIdHash()).channel(channel.name()));
        });
    }

    private DeviceSecurityException tooSoon(DeviceOtpChallenge c, long wait, String message) {
        Map<String, Object> body = viewMap(view(c));
        return new DeviceSecurityException(HttpStatus.TOO_MANY_REQUESTS, "resend_too_soon", message, body, wait);
    }

    // ---- verify --------------------------------------------------------------------

    /**
     * Checks a code. Joins the caller's transaction, which MUST commit even on a
     * wrong code — the spent attempt is the whole brute-force defence — so the
     * caller turns WRONG/DEAD into an HTTP refusal only after committing.
     */
    @Transactional
    public VerifyOutcome verify(String challengeId, String code, String presentedInstallHash,
                                Set<SignInPurpose> purposes, ActorType actor) {
        LocalDateTime now = now();
        DeviceOtpChallenge c = challenges.findById(challengeId).orElse(null);
        if (c == null || !purposes.contains(c.getPurpose()) || !c.liveAt(now)) {
            return new VerifyOutcome(VerifyStatus.GONE, c);
        }
        if (c.getCodeHash() == null) {
            return new VerifyOutcome(VerifyStatus.NOT_SENT, c);
        }
        boolean sameDevice = presentedInstallHash != null && presentedInstallHash.equals(c.getInstallIdHash());
        if (!sameDevice) {
            // §8.2 OTP relay: a code typed on a phone other than the one that asked for it.
            events.record(SecurityEventType.OTP_RELAY_SUSPECTED, actor, null, c.getMsisdn(), null,
                    b -> b.installIdHash(presentedInstallHash).reason("VERIFY_FROM_OTHER_DEVICE")
                            .note("challenge " + c.getId()));
            metrics.fraudDeskAlert("otp_relay");
        }
        boolean match = sameDevice && constantTimeEquals(c.getCodeHash(), otpHasher.hash(code));
        if (match) {
            c.close(ChallengeStatus.VERIFIED, now);
            challenges.save(c);
            events.record(SecurityEventType.OTP_VERIFIED, actor, null, c.getMsisdn(), null,
                    b -> b.installIdHash(c.getInstallIdHash()).channel(name(c.getChannel()))
                            .purpose(c.getPurpose().name()).reason(c.getReason()));
            metrics.otp("verified", c.getChannel());
            return new VerifyOutcome(VerifyStatus.VERIFIED, c);
        }
        c.setAttemptsLeft(Math.max(0, c.getAttemptsLeft() - 1));
        if (c.getAttemptsLeft() == 0) {
            c.close(ChallengeStatus.DEAD, now);
            challenges.save(c);
            events.record(SecurityEventType.OTP_CHALLENGE_DEAD, actor, null, c.getMsisdn(), null,
                    b -> b.installIdHash(c.getInstallIdHash()).channel(name(c.getChannel())));
            metrics.otp("dead", c.getChannel());
            return new VerifyOutcome(VerifyStatus.DEAD, c);
        }
        challenges.save(c);
        events.record(SecurityEventType.OTP_WRONG, actor, null, c.getMsisdn(), null,
                b -> b.installIdHash(c.getInstallIdHash()).channel(name(c.getChannel())));
        metrics.otp("wrong", c.getChannel());
        return new VerifyOutcome(VerifyStatus.WRONG, c);
    }

    /** The number a challenge belongs to. */
    @Transactional(readOnly = true)
    public Optional<String> msisdnOf(String challengeId) {
        return challenges.findById(challengeId).map(DeviceOtpChallenge::getMsisdn);
    }

    // ---- void ----------------------------------------------------------------------

    /** Kills every open challenge on a device. Joins the caller's transaction. */
    @Transactional
    public int voidForDevice(CustomerDevice device) {
        return challenges.voidOpenForDevice(device.getId(), now());
    }

    /** Kills every open challenge for a number (call centre: "I got a code I didn't ask for"). */
    @Transactional
    public int voidForNumber(String msisdn) {
        LocalDateTime now = now();
        int n = 0;
        for (DeviceOtpChallenge c : challenges.findByMsisdnAndStatus(msisdn, ChallengeStatus.OPEN)) {
            c.close(ChallengeStatus.VOID, now);
            challenges.save(c);
            n++;
        }
        return n;
    }

    // ---- helpers -------------------------------------------------------------------

    private DeviceOtpChallenge live(String challengeId, String presentedInstallHash,
                                    Set<SignInPurpose> purposes, LocalDateTime now) {
        DeviceOtpChallenge c = challenges.findById(challengeId).orElse(null);
        if (c == null || !purposes.contains(c.getPurpose()) || !c.liveAt(now)
                || presentedInstallHash == null || !presentedInstallHash.equals(c.getInstallIdHash())) {
            throw gone(challengeId);
        }
        return c;
    }

    public static DeviceSecurityException gone(String challengeId) {
        return new DeviceSecurityException(HttpStatus.GONE, "challenge_expired",
                "This code has expired or can no longer be used. Please start again.",
                challengeId == null ? null : Map.of("challengeId", challengeId), null);
    }

    public OtpSendResponse view(DeviceOtpChallenge c) {
        return new OtpSendResponse(c.getId(), c.getChannel(), DeviceIdentity.destinationMasked(c.getMsisdn()),
                c.getExpiresAt(), c.getResendAfter(), c.getAttemptsLeft(), c.getResendsLeft());
    }

    static Map<String, Object> viewMap(OtpSendResponse r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("challengeId", r.challengeId());
        m.put("channel", r.channel());
        m.put("destinationMasked", r.destinationMasked());
        m.put("expiresAt", r.expiresAt());
        m.put("resendAfter", r.resendAfter());
        m.put("attemptsLeft", r.attemptsLeft());
        m.put("resendsLeft", r.resendsLeft());
        return m;
    }

    /**
     * Allow-listed test numbers get a fixed code and no message (§11, §14.1). Refused
     * on production whatever the config says: a fixed code there is a skeleton key.
     */
    boolean isTestNumber(String msisdn) {
        DeviceSecurityProperties.TestOtp t = properties.getTestOtp();
        return t.isEnabled() && !properties.isProduction()
                && t.getCode() != null && t.getCode().matches("\\d{6}")
                && t.getNumbers() != null && t.getNumbers().contains(msisdn);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** For tests: which channels are currently marked down. */
    Map<OtpChannel, LocalDateTime> downChannels() {
        return Map.copyOf(downUntil);
    }
}
