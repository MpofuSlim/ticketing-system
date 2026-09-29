package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.devicesecurity.DeviceOtpService.OtpCeilingException;
import com.innbucks.userservice.devicesecurity.DeviceOtpService.VerifyOutcome;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.CustomerDeviceView;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.CustomerDevicesResponse;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.StepUpRequest;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.StepUpResponse;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.StepUpVerifyRequest;
import com.innbucks.userservice.devicesecurity.dto.CustomerDeviceDTOs.StepUpVerifyResponse;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpSendRequest;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpSendResponse;
import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import com.innbucks.userservice.devicesecurity.entity.CustomerSecurityProfile;
import com.innbucks.userservice.devicesecurity.entity.DeviceOtpChallenge;
import com.innbucks.userservice.devicesecurity.repository.CustomerDeviceRepository;
import com.innbucks.userservice.devicesecurity.repository.CustomerSecurityProfileRepository;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.AuthenticatedCaller;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The signed-in customer's own view of their phones (contract §5.6) and the
 * in-session step-up (§5.5).
 *
 * <p><b>Which session.</b> DTX cannot verify staging's banking token (it holds no
 * key for it), so these endpoints authenticate with the customer's FLEET session
 * — the CUSTOMER token the app already holds from {@code POST /auth/exchange} —
 * and the number comes from that account, never from the request. Every call
 * still arrives through the broker with its {@code x-api-key}.
 *
 * <p>Nothing here returns another phone's install id, location or risk facts
 * (§12): a phone is named by its opaque {@code deviceId}, its label and the
 * nearest town.
 */
@Service
@Slf4j
public class CustomerDeviceService {

    static final Set<SignInPurpose> SESSION_PURPOSES = Set.of(SignInPurpose.SESSION_STEP_UP);

    private final CustomerDeviceRepository devices;
    private final CustomerSecurityProfileRepository profiles;
    private final UserRepository users;
    private final DeviceEnforcement enforcement;
    private final DeviceOtpService otp;
    private final DeviceSecurityEventLog events;
    private final DeviceSecurityMessages messages;
    private final LoginTicketSigner signer;
    private final DeviceSecurityProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String deploymentCountry;

    public CustomerDeviceService(CustomerDeviceRepository devices, CustomerSecurityProfileRepository profiles,
                                 UserRepository users, DeviceEnforcement enforcement, DeviceOtpService otp,
                                 DeviceSecurityEventLog events, DeviceSecurityMessages messages,
                                 LoginTicketSigner signer, DeviceSecurityProperties properties,
                                 Clock deviceSecurityClock, PlatformTransactionManager transactionManager,
                                 @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.devices = devices;
        this.profiles = profiles;
        this.users = users;
        this.enforcement = enforcement;
        this.otp = otp;
        this.events = events;
        this.messages = messages;
        this.signer = signer;
        this.properties = properties;
        this.clock = deviceSecurityClock;
        this.tx = new TransactionTemplate(transactionManager);
        this.deploymentCountry = deploymentCountry;
    }

    // ---- GET /auth/devices ----------------------------------------------------------

    public CustomerDevicesResponse list(Authentication auth, String headerInstallId) {
        String msisdn = callerMsisdn(auth);
        String currentHash = headerInstallId == null || headerInstallId.isBlank() ? null
                : DeviceIdentity.hashInstallId(headerInstallId);
        return tx.execute(s -> {
            LocalDateTime now = now();
            List<CustomerDeviceView> out = new ArrayList<>();
            long trusted = 0;
            for (CustomerDevice d : devices.findByMsisdnOrderByLastSeenAtDesc(msisdn)) {
                enforcement.refresh(d, false);
                boolean current = d.getInstallIdHash().equals(currentHash);
                if (d.getState() == DeviceState.REVOKED) continue;
                // "Each bound device" (§5.6): an abandoned first attempt is noise, but the
                // phone asking, and any blocked phone, are always shown.
                if (d.getBoundAt() == null && !current && !d.getState().isStopped()) continue;
                if (d.trustedAt(now)) trusted++;
                boolean wide = enforcement.activeWideBan(d.getInstallIdHash()).isPresent();
                out.add(new CustomerDeviceView(d.getPublicId(), d.getLabel() == null
                        ? DeviceEnforcement.shortLabel(d) : d.getLabel(), d.getFirstSeenAt(), d.getLastSeenAt(),
                        d.getLastSeenNear(), current, wide ? DeviceState.BANNED.name() : d.getState().name(),
                        status(d, wide, now), d.getTrustedUntil()));
            }
            return new CustomerDevicesResponse(out, trusted > properties.getTrust().getMaxTrustedDevices());
        });
    }

    private String status(CustomerDevice d, boolean wide, LocalDateTime now) {
        if (wide) return "Blocked. Call InnBucks support";
        return switch (d.getState()) {
            case TRUSTED -> d.trustedAt(now) ? "Signed in" : "Needs a code at next sign-in";
            case PENDING_PIN -> "Signing in";
            case STEP_UP, NEW -> "Needs a code at next sign-in";
            case TEMP_BLOCKED -> d.getBlockedUntil() == null ? "Paused while its security check fails"
                    : "Paused until " + messages.time(d.getBlockedUntil(), now, false);
            case BANNED -> d.isUssdUnlockable() ? "Blocked. Dial *569# to unlock" : "Blocked. Call InnBucks support";
            case REVOKED -> "Removed";
        };
    }

    // ---- DELETE /auth/devices/{deviceId} ---------------------------------------------

    public String remove(Authentication auth, UUID deviceId) {
        String msisdn = callerMsisdn(auth);
        return tx.execute(s -> {
            CustomerDevice d = devices.findByPublicIdAndMsisdn(deviceId, msisdn)
                    .filter(x -> x.getState() != DeviceState.REVOKED)
                    .orElseThrow(() -> new DeviceSecurityException(HttpStatus.NOT_FOUND, "device_not_found",
                            "We couldn't find that phone on your account."));
            if (d.getState() == DeviceState.BANNED) {
                // Removing would turn a ban into a fresh NEW row — a way round it.
                throw new DeviceSecurityException(HttpStatus.CONFLICT, "device_blocked",
                        "This phone is blocked, so it can't be removed. Dial *569# to manage it.");
            }
            enforcement.revoke(d, new DeviceEnforcement.Actor(ActorType.CUSTOMER, callerId(auth), null, null),
                    "Removed by the customer in Your devices");
            return DeviceEnforcement.shortLabel(d);
        });
    }

    // ---- POST /auth/device/challenge (§5.5) ----------------------------------------

    public DeviceSignInService.Answer<StepUpResponse> stepUp(Authentication auth, String headerInstallId,
                                                             StepUpRequest req, String ip) {
        String msisdn = callerMsisdn(auth);
        String installId = DeviceSignInService.requireHeaderInstallId(
                new DeviceSignInService.RequestMeta(ip, headerInstallId, null, null));
        String hash = DeviceIdentity.hashInstallId(installId);
        boolean force = Boolean.TRUE.equals(req.force());
        record Result(StepUpResponse body, Long retryAfter) {
        }
        Result r = tx.execute(s -> {
            LocalDateTime now = now();
            CustomerDevice d = devices.findByMsisdnAndInstallIdHash(msisdn, hash)
                    .filter(x -> x.trustedAt(now) || x.inPinGrace(now))
                    .orElseThrow(() -> new DeviceSecurityException(HttpStatus.FORBIDDEN, "device_not_trusted",
                            "This phone isn't signed in to your InnBucks. Please sign in again."));
            CustomerSecurityProfile profile = profiles.findById(msisdn).orElse(null);
            boolean recentlyBound = d.getBoundAt() == null
                    || d.getBoundAt().isAfter(now.minus(properties.getTrust().getSessionStepUpWindow()));
            boolean flagged = profile != null && profile.fraudFlagged();
            // A phone trusted only by watch mode has never proved the SIM: ask, once codes are on.
            boolean required = force || (properties.getEnforce().isOtp()
                    && (recentlyBound || flagged || !d.possessionVerified()));
            if (!required) {
                events.record(SecurityEventType.SESSION_STEP_UP_NOT_REQUIRED, ActorType.CUSTOMER, callerId(auth),
                        msisdn, d, b -> b.reason(req.action()).ipAddress(ip));
                return new Result(new StepUpResponse("NOT_REQUIRED", null, null, null, null), null);
            }
            DeviceOtpService.Opened opened;
            try {
                opened = otp.open(d, SignInPurpose.SESSION_STEP_UP, OtpReason.POLICY.name(), ip, null, req.action(),
                        ActorType.CUSTOMER);
            } catch (OtpCeilingException e) {
                return new Result(null, e.retryAfterSeconds());
            }
            List<OtpChannel> channels = otp.channels(msisdn);
            return new Result(new StepUpResponse("OTP_REQUIRED", opened.challenge().getId(), channels,
                    otp.defaultChannel(profile == null ? null : profile.getPreferredChannel(), channels),
                    DeviceIdentity.destinationMasked(msisdn)), null);
        });
        if (r.retryAfter() != null) throw DeviceSignInService.rateLimited(r.retryAfter());
        return new DeviceSignInService.Answer<>(r.body(), "NOT_REQUIRED".equals(r.body().decision())
                ? "No extra check is needed. You can continue."
                : "For your security, let's confirm it's you before you continue.");
    }

    public OtpSendResponse sendStepUp(Authentication auth, String headerInstallId, OtpSendRequest req) {
        String msisdn = callerMsisdn(auth);
        String installId = DeviceSignInService.requireHeaderInstallId(
                new DeviceSignInService.RequestMeta(null, headerInstallId, null, null));
        if (!otp.msisdnOf(req.challengeId()).map(msisdn::equals).orElse(false)) {
            throw DeviceOtpService.gone(req.challengeId());
        }
        return otp.send(req.challengeId(), OtpChannel.valueOf(req.channel()), DeviceIdentity.hashInstallId(installId),
                SESSION_PURPOSES, ActorType.CUSTOMER);
    }

    public DeviceSignInService.Answer<StepUpVerifyResponse> verifyStepUp(Authentication auth, String headerInstallId,
                                                                         StepUpVerifyRequest req) {
        String msisdn = callerMsisdn(auth);
        String installId = DeviceSignInService.requireHeaderInstallId(
                new DeviceSignInService.RequestMeta(null, headerInstallId, null, null));
        String hash = DeviceIdentity.hashInstallId(installId);
        if (!otp.msisdnOf(req.challengeId()).map(msisdn::equals).orElse(false)) {
            throw DeviceOtpService.gone(req.challengeId());
        }
        VerifyOutcome v = tx.execute(s -> {
            VerifyOutcome outcome = otp.verify(req.challengeId(), req.otp(), hash, SESSION_PURPOSES, ActorType.CUSTOMER);
            if (outcome.status() == DeviceOtpService.VerifyStatus.VERIFIED) {
                DeviceOtpChallenge c = outcome.challenge();
                events.record(SecurityEventType.SESSION_STEP_UP_VERIFIED, ActorType.CUSTOMER, callerId(auth), msisdn,
                        null, b -> b.installIdHash(hash).reason(c.getSessionAction()).channel(
                                c.getChannel() == null ? null : c.getChannel().name()));
            }
            return outcome;
        });
        DeviceOtpChallenge c = v.challenge();
        switch (v.status()) {
            case VERIFIED -> {
                if (!signer.isConfigured()) throw DeviceSecurityException.tryAgainShortly();
                LocalDateTime expires = now().plus(properties.getTicket().getStepUpProofTtl());
                String proof = signer.signStepUpProof(msisdn, installId, c.getSessionAction(),
                        expires.toInstant(ZoneOffset.UTC));
                return new DeviceSignInService.Answer<>(new StepUpVerifyResponse(true, c.getSessionAction(), proof,
                        expires), "Thanks, that's confirmed. You can continue.");
            }
            case WRONG -> throw new DeviceSecurityException(HttpStatus.UNAUTHORIZED, "otp_incorrect",
                    "That code isn't right. You have " + c.getAttemptsLeft()
                            + (c.getAttemptsLeft() == 1 ? " attempt" : " attempts") + " left.",
                    Map.of("challengeId", c.getId(), "attemptsLeft", c.getAttemptsLeft()), null);
            case DEAD -> throw new DeviceSecurityException(HttpStatus.GONE, "challenge_expired",
                    "That code isn't right, and it can no longer be used. Please start again.",
                    Map.of("challengeId", c.getId(), "attemptsLeft", 0), null);
            case NOT_SENT -> throw new DeviceSecurityException(HttpStatus.BAD_REQUEST, "otp_not_sent",
                    "Choose WhatsApp or SMS so we can send you a code first.", Map.of("challengeId", c.getId()), null);
            default -> throw DeviceOtpService.gone(req.challengeId());
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    /** The number of the signed-in customer — from their account, never from the request. */
    String callerMsisdn(Authentication auth) {
        UUID uuid = AuthenticatedCaller.userUuid(auth);
        User user = uuid == null ? null : users.findByUserUuid(uuid).orElse(null);
        String phone = user == null ? null : user.getPhoneNumber();
        String msisdn = phone == null ? null : MsisdnValidator.normalizeToE164(phone, deploymentCountry).orElse(null);
        if (msisdn == null) {
            throw new DeviceSecurityException(HttpStatus.FORBIDDEN, "no_phone_on_account",
                    "Your account has no mobile number, so it has no phones to manage.");
        }
        return msisdn;
    }

    private static String callerId(Authentication auth) {
        UUID uuid = AuthenticatedCaller.userUuid(auth);
        return uuid == null ? (auth == null ? null : auth.getName()) : uuid.toString();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
