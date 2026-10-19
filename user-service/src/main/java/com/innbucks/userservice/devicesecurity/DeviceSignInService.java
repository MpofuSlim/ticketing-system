package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.devicesecurity.DeviceOtpService.OtpCeilingException;
import com.innbucks.userservice.devicesecurity.DeviceOtpService.VerifyOutcome;
import com.innbucks.userservice.devicesecurity.StagingClientServiceClient.ClientServiceToken;
import com.innbucks.userservice.devicesecurity.StagingClientServiceClient.StagingUnavailableException;
import com.innbucks.userservice.devicesecurity.dto.BrokerDTOs.LoginResultRequest;
import com.innbucks.userservice.devicesecurity.dto.BrokerDTOs.LoginResultResponse;
import com.innbucks.userservice.devicesecurity.dto.BrokerDTOs.TicketRedeemRequest;
import com.innbucks.userservice.devicesecurity.dto.BrokerDTOs.TicketRedeemResponse;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.BannedDecision;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.ClientServiceRequest;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.ClientServiceTokenView;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.DeviceFacts;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.LimitsView;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpRequiredDecision;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpSendRequest;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpSendResponse;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.OtpVerifyRequest;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.SignInDecision;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.TempBlockedDecision;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.TokenDecision;
import com.innbucks.userservice.devicesecurity.dto.DeviceSignInDTOs.TrustView;
import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import com.innbucks.userservice.devicesecurity.entity.CustomerSecurityProfile;
import com.innbucks.userservice.devicesecurity.entity.DeviceLoginTicket;
import com.innbucks.userservice.devicesecurity.entity.DeviceOtpChallenge;
import com.innbucks.userservice.devicesecurity.entity.DeviceWideBan;
import com.innbucks.userservice.devicesecurity.entity.SignInLocation;
import com.innbucks.userservice.devicesecurity.repository.CustomerDeviceRepository;
import com.innbucks.userservice.devicesecurity.repository.CustomerSecurityProfileRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceLoginTicketRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceSecurityEventRepository;
import com.innbucks.userservice.devicesecurity.repository.SignInLocationRepository;
import com.innbucks.userservice.devicesecurity.repository.TicketRef;
import com.innbucks.userservice.util.MsisdnValidator;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * DTX sign-in: the device check that now stands in front of staging's
 * {@code /auth/client-service} (contract §1–§8).
 *
 * <p><b>Transaction shape.</b> Each call decides inside one short transaction
 * (registry reads, the rules, the resulting state change and the decision-log
 * row commit together), then does any network work — fetching staging's token —
 * OUTSIDE it, so a slow staging never holds a pooled connection. Refusals that
 * must still commit (a wrong OTP spends an attempt) are returned from the
 * transaction and thrown only after it has committed.
 *
 * <p><b>Watch mode (§14).</b> When a rule family is not enforced, its verdict is
 * logged as {@code evaluated_decision} and the caller gets TOKEN. A device that
 * would have needed an OTP is moved to PENDING_PIN instead, so the broker's
 * successful login still binds it and the registry learns real devices from day
 * one.
 */
@Service
@Slf4j
public class DeviceSignInService {

    static final Set<SignInPurpose> SIGN_IN_CHALLENGE_PURPOSES = Set.of(SignInPurpose.SIGN_IN, SignInPurpose.PIN_ISSUE);

    /** What the controller knows about the HTTP request. */
    public record RequestMeta(String ip, String headerInstallId, String appCheck, String userAgent) {
    }

    /** A decision body plus the line to show the customer ({@code ApiResult.message}). */
    public record Answer<T>(T body, String message) {
    }

    private enum Kind { TOKEN, OTP, TEMP_BLOCKED, BANNED, RATE_LIMITED }

    /**
     * @param signsOutOthers TOKEN only: the PIN about to be typed binds a phone that will
     *                       sign out another phone the customer has been using (one phone
     *                       per number), so the line says so
     */
    private record Decided(Kind kind, CustomerDevice device, DeviceOtpChallenge challenge, List<OtpChannel> channels,
                           OtpChannel defaultChannel, String reason, String supportRef, LocalDateTime blockedUntil,
                           boolean ussdUnlock, long retryAfter, boolean signsOutOthers) {
        static Decided token(CustomerDevice d) {
            return new Decided(Kind.TOKEN, d, null, null, null, null, null, null, false, 0, false);
        }

        static Decided banned(CustomerDevice d, String reason, boolean ussdUnlock, String ref) {
            return new Decided(Kind.BANNED, d, null, null, null, reason, ref, null, ussdUnlock, 0, false);
        }

        static Decided tempBlocked(CustomerDevice d) {
            return new Decided(Kind.TEMP_BLOCKED, d, null, null, null, d.getStateReason(), d.getSupportRef(),
                    d.getBlockedUntil(), false, 0, false);
        }

        static Decided rateLimited(long retryAfter) {
            return new Decided(Kind.RATE_LIMITED, null, null, null, null, null, null, null, false, retryAfter, false);
        }

        Decided signingOutOthers() {
            return new Decided(kind, device, challenge, channels, defaultChannel, reason, supportRef, blockedUntil,
                    ussdUnlock, retryAfter, true);
        }

        Decision decision() {
            return switch (kind) {
                case TOKEN -> Decision.TOKEN;
                case OTP -> Decision.OTP_REQUIRED;
                case TEMP_BLOCKED -> Decision.TEMP_BLOCKED;
                case BANNED -> Decision.BANNED;
                case RATE_LIMITED -> null;
            };
        }
    }

    private final DeviceSecurityProperties properties;
    private final CustomerDeviceRepository devices;
    private final CustomerSecurityProfileRepository profiles;
    private final DeviceLoginTicketRepository tickets;
    private final SignInLocationRepository locations;
    private final DeviceSecurityEventRepository eventRepository;
    private final RiskEngine engine;
    private final DeviceOtpService otp;
    private final DeviceEnforcement enforcement;
    private final NumberLock numberLock;
    private final DeviceSecurityEventLog events;
    private final DeviceSecurityMetrics metrics;
    private final StagingClientServiceClient staging;
    private final LoginTicketSigner signer;
    private final DeviceSecurityMessages messages;
    private final TownGazetteer gazetteer;
    private final CellAffinityChecker cellAffinity;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String deploymentCountry;

    public DeviceSignInService(DeviceSecurityProperties properties,
                               CustomerDeviceRepository devices,
                               CustomerSecurityProfileRepository profiles,
                               DeviceLoginTicketRepository tickets,
                               SignInLocationRepository locations,
                               DeviceSecurityEventRepository eventRepository,
                               RiskEngine engine,
                               DeviceOtpService otp,
                               DeviceEnforcement enforcement,
                               NumberLock numberLock,
                               DeviceSecurityEventLog events,
                               DeviceSecurityMetrics metrics,
                               StagingClientServiceClient staging,
                               LoginTicketSigner signer,
                               DeviceSecurityMessages messages,
                               TownGazetteer gazetteer,
                               CellAffinityChecker cellAffinity,
                               ApplicationEventPublisher publisher,
                               Clock deviceSecurityClock,
                               PlatformTransactionManager transactionManager,
                               @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.properties = properties;
        this.devices = devices;
        this.profiles = profiles;
        this.tickets = tickets;
        this.locations = locations;
        this.eventRepository = eventRepository;
        this.engine = engine;
        this.otp = otp;
        this.enforcement = enforcement;
        this.numberLock = numberLock;
        this.events = events;
        this.metrics = metrics;
        this.staging = staging;
        this.signer = signer;
        this.messages = messages;
        this.gazetteer = gazetteer;
        this.cellAffinity = cellAffinity;
        this.publisher = publisher;
        this.clock = deviceSecurityClock;
        this.tx = new TransactionTemplate(transactionManager);
        this.deploymentCountry = deploymentCountry;
    }

    // =====================================================================================
    // POST /auth/client-service
    // =====================================================================================

    public Answer<SignInDecision> decide(ClientServiceRequest req, RequestMeta meta) {
        requireEnabled();
        SignInPurpose purpose = req.purpose() == null ? SignInPurpose.SIGN_IN : SignInPurpose.valueOf(req.purpose());
        SignInContext context = req.context() == null ? SignInContext.SIGN_IN : SignInContext.valueOf(req.context());
        String msisdn = normalise(req.msisdn(), "msisdn");
        cellAffinity.requireDomesticMsisdn(msisdn);
        String installId = req.device().installId().trim();
        if (meta.headerInstallId() != null && !meta.headerInstallId().isBlank()
                && !meta.headerInstallId().trim().equalsIgnoreCase(installId)) {
            throw DeviceSecurityException.badRequest("device.installId",
                    "device.installId must match the x-device-id header.");
        }
        if (meta.appCheck() != null && "invalid".equalsIgnoreCase(meta.appCheck().trim())) {
            // The broker said the app is not ours. Not a decision about the device —
            // a request we will not act on at all.
            tx.executeWithoutResult(s -> events.record(SecurityEventType.SIGN_IN_DECISION, ActorType.APP, null, msisdn,
                    null, b -> b.installIdHash(DeviceIdentity.hashInstallId(installId)).reason("APP_CHECK_INVALID")
                            .purpose(purpose.name()).context(context.name()).requestId(req.requestId()).ipAddress(meta.ip())));
            throw new DeviceSecurityException(HttpStatus.FORBIDDEN, "app_check_failed",
                    "Please update InnBucks from the official app store, then try again.");
        }
        String installHash = DeviceIdentity.hashInstallId(installId);

        Decided d = tx.execute(s -> purpose == SignInPurpose.LOOKUP
                ? decideLookup(req, msisdn, installHash, meta)
                : decideSignIn(req, purpose, context, msisdn, installHash, meta));

        return switch (d.kind()) {
            case TOKEN -> {
                Answer<TokenDecision> token = issueToken(d.device(), msisdn, installId, installHash, purpose, context,
                        false, meta.ip(), d.signsOutOthers() ? messages.tokenLineSigningOutOthers() : messages.tokenLine());
                yield new Answer<>(token.body(), token.message());
            }
            case OTP -> new Answer<>(new OtpRequiredDecision(Decision.OTP_REQUIRED, d.challenge().getId(), d.channels(),
                    d.defaultChannel(), DeviceIdentity.destinationMasked(msisdn), d.reason()), messages.otpRequiredLine());
            case TEMP_BLOCKED -> new Answer<>(new TempBlockedDecision(Decision.TEMP_BLOCKED, d.blockedUntil(), d.reason(),
                    d.supportRef()), messages.tempBlockedLine(d.blockedUntil(), d.supportRef(), now()));
            case BANNED -> new Answer<>(new BannedDecision(Decision.BANNED, d.reason(), d.ussdUnlock(),
                    DeviceSecurityMessages.USSD_CODE, messages.supportPhone(), d.supportRef()),
                    messages.bannedLine(d.ussdUnlock(), d.supportRef()));
            case RATE_LIMITED -> throw rateLimited(d.retryAfter());
        };
    }

    private Decided decideSignIn(ClientServiceRequest req, SignInPurpose purpose, SignInContext context,
                                 String msisdn, String installHash, RequestMeta meta) {
        LocalDateTime now = now();
        CustomerSecurityProfile profile = profile(msisdn, now);
        devices.insertIfAbsent(UUID.randomUUID(), msisdn, installHash, now);
        CustomerDevice device = devices.findByMsisdnAndInstallIdHash(msisdn, installHash).orElseThrow();
        Set<IntegrityThreat> threats = IntegrityThreat.classify(req.integrity() == null ? null : req.integrity().threats());

        Optional<DeviceWideBan> wide = enforcement.activeWideBan(installHash);
        if (wide.isPresent()) {
            Decided banned = Decided.banned(device, wide.get().getReason(), false, wide.get().getSupportRef());
            logDecision(banned, null, device, msisdn, purpose, context, req, meta, "DEVICE_WIDE_BAN");
            return banned;
        }

        enforcement.refresh(device, !threats.contains(IntegrityThreat.ROOT));
        if (device.getState() == DeviceState.BANNED) {
            Decided banned = Decided.banned(device, device.getStateReason(), device.isUssdUnlockable(), device.getSupportRef());
            touch(device, req, meta, now, false);
            logDecision(banned, null, device, msisdn, purpose, context, req, meta, null);
            return banned;
        }
        if (device.getState() == DeviceState.TEMP_BLOCKED) {
            Decided blocked = Decided.tempBlocked(device);
            touch(device, req, meta, now, false);
            logDecision(blocked, null, device, msisdn, purpose, context, req, meta, null);
            return blocked;
        }

        RiskEngine.Assessment a = engine.assess(input(req, purpose, context, device, profile, threats, meta.appCheck(), now));
        touch(device, req, meta, now, true);
        Decided result = apply(a, device, profile, purpose, meta, req.requestId(), now);
        // A PIN retry inside the grace that a fresh code opened: the bind it leads to still
        // signs the number's other phone out, so the line still says so.
        if (result.kind() == Kind.TOKEN && enforcement.wouldSignOutAPhoneInUse(device)) {
            result = result.signingOutOthers();
        }
        logDecision(result, a, device, msisdn, purpose, context, req, meta, null);
        return result;
    }

    private RiskEngine.Input input(ClientServiceRequest req, SignInPurpose purpose, SignInContext context,
                                   CustomerDevice device, CustomerSecurityProfile profile,
                                   Set<IntegrityThreat> threats, String appCheck, LocalDateTime now) {
        DeviceFacts f = req.device();
        List<SignInLocation> history = locations.findTop200ByMsisdnOrderByOccurredAtDesc(device.getMsisdn());
        RiskEngine.LastFix last = history.isEmpty() ? null
                : new RiskEngine.LastFix(history.get(0).getLat(), history.get(0).getLng(), history.get(0).getOccurredAt());
        List<double[]> fixes = new ArrayList<>(history.size());
        for (SignInLocation l : history) fixes.add(new double[]{l.getLat(), l.getLng()});

        long trusted = devices.countByMsisdnAndState(device.getMsisdn(), DeviceState.TRUSTED);
        long otherTrusted = device.getState() == DeviceState.TRUSTED ? trusted - 1 : trusted;
        Set<String> numbers = new HashSet<>(eventRepository.distinctNumbersOnDeviceSince(device.getInstallIdHash(),
                now.minusHours(1)));
        numbers.add(device.getMsisdn());
        long wrongPins = tickets.countByInstallIdHashAndOutcomeAndOutcomeAtAfter(device.getInstallIdHash(),
                LoginOutcome.WRONG_PIN, now.minusDays(1));

        // Provisional trust (granted by watch mode, never confirmed with a code) is
        // not trust once OTP is enforced: the engine sees the phone as never proven,
        // so it is asked for one code — on a silent renewal too — and is trusted
        // for real after it. While only watching, the engine sees the stored state,
        // so the logged verdicts stay those of the steady state rather than every
        // renewal reading as "would have asked for a code".
        boolean provisional = properties.getEnforce().isOtp() && device.getState() == DeviceState.TRUSTED
                && !device.possessionVerified();
        var loc = req.location();
        return new RiskEngine.Input(now, purpose, context,
                provisional ? DeviceState.NEW : device.getState(), provisional ? null : device.getStateReason(),
                provisional ? null : device.getTrustedUntil(), device.getBoundAt() != null,
                device.inPinGrace(now),
                device.getPlatform(), device.getManufacturer(), device.getModel(),
                f.platform(), f.manufacturer(), f.model(),
                threats, req.integrity() == null ? null : req.integrity().source(),
                appCheck,
                loc == null ? null : loc.status(), loc == null ? null : GeoMath.round3(loc.lat()),
                loc == null ? null : GeoMath.round3(loc.lng()), loc == null ? null : loc.mocked(),
                profile.fraudFlagged(), profile.getPinIssuedAt(), profile.getLastSignInAt(), last, fixes,
                otherTrusted, devices.countOtherBoundCustomers(device.getInstallIdHash(), device.getMsisdn()),
                numbers.size(), wrongPins,
                properties.isProduction(), properties.getTrust(), properties.getRisk());
    }

    private Decided apply(RiskEngine.Assessment a, CustomerDevice device, CustomerSecurityProfile profile,
                          SignInPurpose purpose, RequestMeta meta, String requestId, LocalDateTime now) {
        DeviceSecurityProperties.Enforce enforce = properties.getEnforce();
        switch (a.verdict()) {
            case BAN -> {
                if (enforce.isBans()) {
                    enforcement.ban(device, a.banReason(), DeviceEnforcement.Actor.system(), null, a.signals());
                    return Decided.banned(device, device.getStateReason(), device.isUssdUnlockable(), device.getSupportRef());
                }
            }
            case TEMP_BLOCK, INTEGRITY_HOLD -> {
                if (enforce.isBlocks()) {
                    enforcement.tempBlock(device, a.blockReason(), null, DeviceEnforcement.Actor.system(), null, a.signals());
                    return stopped(device);
                }
            }
            case STEP_UP -> {
                if (enforce.isOtp()) {
                    return otpRequired(device, profile, purpose, a.otpReason(), meta, requestId, now);
                }
            }
            case ALLOW -> {
                return allow(device, now);
            }
        }
        // A block or ban the rollout has not switched on yet still earns the strongest
        // family that IS on: with OTP enforced, a would-be block asks for a code rather
        // than handing a TOKEN to a phone the engine did not trust.
        if (a.verdict() != RiskEngine.Verdict.STEP_UP && enforce.isOtp()) {
            return otpRequired(device, profile, purpose, OtpReason.RISK, meta, requestId, now);
        }
        return allow(device, now);
    }

    private Decided otpRequired(CustomerDevice device, CustomerSecurityProfile profile, SignInPurpose purpose,
                                OtpReason reason, RequestMeta meta, String requestId, LocalDateTime now) {
        DeviceOtpService.Opened opened;
        try {
            opened = otp.open(device, purpose, reason.name(), meta.ip(), requestId, null, ActorType.APP);
        } catch (OtpCeilingException ceiling) {
            if (ceiling.scope() != OtpCeilingException.Scope.IP) {
                // §8.4: more than 5 challenges an hour / 20 a day for one number is a
                // block plus a fraud-desk alert, not merely a rate limit.
                metrics.fraudDeskAlert("otp_velocity");
                log.warn("FRAUD_DESK_ALERT otp_velocity scope={} msisdn={} device={}", ceiling.scope(),
                        DeviceIdentity.logMask(device.getMsisdn()), device.getPublicId());
                if (properties.getEnforce().isBlocks()) {
                    enforcement.tempBlock(device, BlockReason.VELOCITY, null, DeviceEnforcement.Actor.system(), null,
                            Map.of("ceiling", ceiling.scope().name()));
                    return stopped(device);
                }
            }
            return Decided.rateLimited(ceiling.retryAfterSeconds());
        }
        if (device.getState() == DeviceState.TRUSTED) {
            device.transition(DeviceState.STEP_UP, reason.name(), ActorType.SYSTEM.name(), now);
            devices.save(device);
        } else if (device.getState() == DeviceState.REVOKED) {
            // A phone another phone of its number signed out keeps saying so while it asks
            // for a code to come back: that is exactly when the customer calls support.
            device.transition(DeviceState.NEW, DeviceEnforcement.REPLACED.equals(device.getStateReason())
                    ? DeviceEnforcement.REPLACED : null, ActorType.SYSTEM.name(), now);
            devices.save(device);
        }
        List<OtpChannel> channels = otp.channels(device.getMsisdn());
        return new Decided(Kind.OTP, device, opened.challenge(), channels,
                otp.defaultChannel(profile.getPreferredChannel(), channels), opened.challenge().getReason(),
                null, null, false, 0, false);
    }

    /** TOKEN. A device that is neither trusted nor inside its PIN grace (watch mode only) is made PENDING_PIN. */
    private Decided allow(CustomerDevice device, LocalDateTime now) {
        if (!device.trustedAt(now) && !device.inPinGrace(now)) {
            device.transition(DeviceState.PENDING_PIN, null, ActorType.SYSTEM.name(), now);
            device.setPinGraceUntil(now.plus(properties.getTrust().getPendingPinGrace()));
            devices.save(device);
        }
        return Decided.token(device);
    }

    private static Decided stopped(CustomerDevice device) {
        return device.getState() == DeviceState.BANNED
                ? Decided.banned(device, device.getStateReason(), device.isUssdUnlockable(), device.getSupportRef())
                : Decided.tempBlocked(device);
    }

    private Decided decideLookup(ClientServiceRequest req, String msisdn, String installHash, RequestMeta meta) {
        LocalDateTime now = now();
        Optional<DeviceWideBan> wide = enforcement.activeWideBan(installHash);
        if (wide.isPresent()) {
            Decided banned = Decided.banned(null, wide.get().getReason(), false, wide.get().getSupportRef());
            logLookup(banned, msisdn, installHash, req, meta, "DEVICE_WIDE_BAN");
            return banned;
        }
        Optional<CustomerDevice> pair = devices.findByMsisdnAndInstallIdHash(msisdn, installHash);
        if (pair.isPresent()) {
            CustomerDevice d = enforcement.refresh(pair.get(), true);
            if (d.getState().isStopped()) {
                Decided stopped = stopped(d);
                logLookup(stopped, msisdn, installHash, req, meta, null);
                return stopped;
            }
        }
        DeviceSecurityProperties.Lookup limits = properties.getLookup();
        LocalDateTime hourAgo = now.minusHours(1);
        String type = SecurityEventType.SIGN_IN_DECISION.name();
        if (eventRepository.countByMsisdnAndEventTypeAndPurposeAndOccurredAtAfter(msisdn, type,
                SignInPurpose.LOOKUP.name(), hourAgo) >= limits.getPerNumberHourly()
                || eventRepository.countByInstallIdHashAndEventTypeAndPurposeAndOccurredAtAfter(installHash, type,
                SignInPurpose.LOOKUP.name(), hourAgo) >= limits.getPerDeviceHourly()) {
            Decided limited = Decided.rateLimited(3600);
            logLookup(limited, msisdn, installHash, req, meta, "RATE_LIMITED");
            return limited;
        }
        Decided token = Decided.token(null);
        logLookup(token, msisdn, installHash, req, meta, null);
        return token;
    }

    // =====================================================================================
    // POST /auth/client-service/otp/send and /otp/verify
    // =====================================================================================

    public Answer<OtpSendResponse> sendOtp(OtpSendRequest req, RequestMeta meta) {
        requireEnabled();
        String installHash = DeviceIdentity.hashInstallId(requireHeaderInstallId(meta));
        OtpChannel channel = OtpChannel.valueOf(req.channel());
        OtpSendResponse sent = otp.send(req.challengeId(), channel, installHash, SIGN_IN_CHALLENGE_PURPOSES, ActorType.APP);
        rememberChannel(sent);
        return new Answer<>(sent, channel == OtpChannel.WHATSAPP
                ? "We've sent a 6-digit code to your WhatsApp on " + sent.destinationMasked() + "."
                : "We've sent a 6-digit code by SMS to " + sent.destinationMasked() + ".");
    }

    public Answer<TokenDecision> verifyOtp(OtpVerifyRequest req, RequestMeta meta) {
        requireEnabled();
        String installId = requireHeaderInstallId(meta);
        String installHash = DeviceIdentity.hashInstallId(installId);
        record Verified(VerifyOutcome outcome, CustomerDevice device, boolean signsOutOthers) {
        }
        Verified v = tx.execute(s -> {
            LocalDateTime now = now();
            // The number's lock first, read by a scalar query so the challenge is loaded only
            // under it: a bind that signs this phone out voids its codes, and this must not
            // verify a copy read before that. Only a live challenge takes it.
            CustomerSecurityProfile locked = otp.liveMsisdnOf(req.challengeId(), now)
                    .map(m -> numberLock.lock(m, now, NumberLock.VERIFY)).orElse(null);
            VerifyOutcome outcome = otp.verify(req.challengeId(), req.otp(), installHash, SIGN_IN_CHALLENGE_PURPOSES,
                    ActorType.APP);
            DeviceOtpChallenge c = outcome.challenge();
            if (c == null) return new Verified(outcome, null, false);
            CustomerDevice device = devices.findById(c.getCustomerDeviceId()).orElse(null);
            if (device == null) return new Verified(outcome, null, false);
            boolean signsOutOthers = false;
            switch (outcome.status()) {
                case VERIFIED -> {
                    // One instant for the move and the proof: possessionProvedThisSignIn() relies on it.
                    device.transition(DeviceState.PENDING_PIN, null, ActorType.APP.name(), now);
                    device.setPinGraceUntil(now.plus(properties.getTrust().getPendingPinGrace()));
                    device.setConsecutiveDeadChallenges(0);
                    device.setOtpVerifiedAt(now);
                    devices.save(device);
                    if (c.getChannel() != null && locked != null) {
                        locked.setPreferredChannel(c.getChannel());
                        locked.setUpdatedAt(now);
                        profiles.save(locked);
                    }
                    signsOutOthers = enforcement.wouldSignOutAPhoneInUse(device);
                }
                case DEAD -> {
                    device.setConsecutiveDeadChallenges(device.getConsecutiveDeadChallenges() + 1);
                    device.setUpdatedAt(now);
                    devices.save(device);
                    if (device.getConsecutiveDeadChallenges() >= properties.getBlocks().getDeadChallengesInARow()
                            && properties.getEnforce().isBlocks()) {
                        enforcement.tempBlock(device, BlockReason.OTP_ATTEMPTS, null, DeviceEnforcement.Actor.system(),
                                null, Map.of("deadChallengesInARow", device.getConsecutiveDeadChallenges()));
                    }
                }
                default -> {
                    // WRONG / GONE / NOT_SENT change nothing on the device.
                }
            }
            return new Verified(outcome, device, signsOutOthers);
        });

        DeviceOtpChallenge c = v.outcome().challenge();
        switch (v.outcome().status()) {
            case VERIFIED -> {
                return issueToken(v.device(), c.getMsisdn(), installId, installHash, c.getPurpose(),
                        SignInContext.SIGN_IN, true, meta.ip(),
                        v.signsOutOthers() ? messages.tokenLineSigningOutOthers() : messages.tokenLine());
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

    // =====================================================================================
    // The TOKEN answer (§7.2)
    // =====================================================================================

    private Answer<TokenDecision> issueToken(CustomerDevice device, String msisdn, String installId, String installHash,
                                             SignInPurpose purpose, SignInContext context, boolean otpVerified,
                                             String ip, String line) {
        if (!signer.isConfigured()) {
            log.error("Device security cannot issue a TOKEN: device-security.ticket.private-key is not provisioned");
            throw DeviceSecurityException.tryAgainShortly();
        }
        ClientServiceToken token;
        try {
            token = staging.token();
        } catch (StagingUnavailableException e) {
            log.warn("Device security cannot issue a TOKEN: {}", e.getMessage());
            throw DeviceSecurityException.tryAgainShortly();
        }
        LocalDateTime now = now();
        LocalDateTime expires = now.plus(properties.getTicket().getTtl());
        String jti = LoginTicketSigner.newId("tkt_");
        tx.executeWithoutResult(s -> tickets.save(DeviceLoginTicket.builder()
                .jti(jti)
                .msisdn(msisdn)
                .customerDeviceId(device == null ? null : device.getId())
                .installIdHash(installHash)
                .purpose(purpose)
                .context(context)
                .otpVerified(otpVerified)
                .lat(device == null ? null : device.getLastSeenLat())
                .lng(device == null ? null : device.getLastSeenLng())
                .requestIp(DeviceSecurityEventLog.truncate(ip, 64))
                .issuedAt(now)
                .expiresAt(expires)
                .build()));
        String ticket = signer.signTicket(jti, msisdn, installId, purpose, expires.toInstant(ZoneOffset.UTC));

        TrustView trust = device == null ? null
                : new TrustView(device.getPublicId(), device.getState().name(), device.getTrustedUntil(),
                device.getBoundAt() == null);
        // Limits are reported as NOT reduced, because nothing reduces them: no service
        // that moves money reads the cooling period yet, so cooling=true would tell the
        // app to show a "lower limits" notice that is false. coolingUntil is still
        // stored on the row and shown to support. Report it here again only in the
        // change that makes the money path enforce it.
        LimitsView limits = new LimitsView(false, null);
        TokenDecision body = new TokenDecision(Decision.TOKEN,
                new ClientServiceTokenView(token.accessToken(), token.expiresAt()), ticket, trust, limits);
        return new Answer<>(body, line);
    }

    // =====================================================================================
    // Broker: POST /device-security/broker/login-result (§5.4)
    // =====================================================================================

    /**
     * Records the broker's result, under the number's {@link NumberLock}: a successful
     * login binds the phone, and with one phone per number the bind signs the number's
     * other phones out, so two phones finishing together must queue rather than both win
     * (the second finds its own ticket voided by the first's sign-out and binds nothing).
     * A lock wait that runs out, a deadlock or a version clash with a writer that takes no
     * lock (a USSD or call-center block) is retried once with fresh reads.
     */
    public LoginResultResponse recordLoginResult(LoginResultRequest req) {
        requireEnabled();
        LoginOutcome outcome = LoginOutcome.valueOf(req.outcome());
        for (int attempt = 1; ; attempt++) {
            try {
                LoginResultResponse response = tx.execute(s -> recordLoginResultOnce(req, outcome));
                if (response.recorded()) metrics.loginResult(outcome);
                return response;
            } catch (ConcurrencyFailureException e) {
                if (attempt >= 2) throw e;
                log.info("Login result for ticket {} clashed with a concurrent change ({}); retrying once",
                        req.ticketId(), e.getClass().getSimpleName());
            }
        }
    }

    private LoginResultResponse recordLoginResultOnce(LoginResultRequest req, LoginOutcome outcome) {
        LocalDateTime now = now();
        TicketRef ref = tickets.findRef(req.ticketId()).orElseThrow(() -> new DeviceSecurityException(
                HttpStatus.NOT_FOUND, "ticket_not_found", "No login ticket with that id.",
                Map.of("ticketId", req.ticketId()), null));
        CustomerSecurityProfile profile = numberLock.lock(ref.msisdn(), now, NumberLock.BROKER);
        // A compare-and-set, not a load-and-save: it locks the ticket before any phone row,
        // and never writes back a stale copy over a concurrent void.
        int recorded = tickets.recordOutcome(req.ticketId(), outcome, now,
                DeviceSecurityEventLog.truncate(req.stagingCode(), 32));
        DeviceLoginTicket t = tickets.findById(req.ticketId()).orElseThrow();
        CustomerDevice device = t.getCustomerDeviceId() == null ? null
                : devices.findById(t.getCustomerDeviceId()).orElse(null);
        if (recorded == 0) {
            return new LoginResultResponse(t.getJti(), t.getOutcome().name(), false,
                    device == null ? null : device.getState().name());
        }
        events.record(SecurityEventType.LOGIN_RESULT, ActorType.BROKER, null, t.getMsisdn(), device,
                b -> b.installIdHash(t.getInstallIdHash()).reason(outcome.name()).purpose(t.getPurpose().name())
                        .context(t.getContext().name()).note(req.stagingCode() == null ? null
                                : "stagingCode " + DeviceSecurityEventLog.truncate(req.stagingCode(), 32)));
        if (device != null && t.getVoidedAt() == null) {
            applyLoginResult(t, device, profile, outcome, now);
        }
        return new LoginResultResponse(t.getJti(), outcome.name(), true,
                device == null ? null : device.getState().name());
    }

    private void applyLoginResult(DeviceLoginTicket t, CustomerDevice device, CustomerSecurityProfile profile,
                                  LoginOutcome outcome, LocalDateTime now) {
        switch (outcome) {
            case SUCCESS -> {
                if (t.getPurpose() == SignInPurpose.PIN_ISSUE) {
                    profile.setPinIssuedAt(now);
                    profile.setUpdatedAt(now);
                    profiles.save(profile);
                    // The customer signs in with the new PIN next, from this same phone:
                    // keep the grace open so that sign-in does not ask for a second code.
                    if (device.getState() == DeviceState.PENDING_PIN) {
                        device.setPinGraceUntil(now.plus(properties.getTrust().getPendingPinGrace()));
                        device.setUpdatedAt(now);
                        devices.save(device);
                    }
                    publisher.publishEvent(new DeviceNotice(DeviceNotice.Type.PIN_SET, t.getMsisdn(),
                            DeviceEnforcement.shortLabel(device), now, null, null, profile.getPreferredChannel(), false));
                    return;
                }
                if (enforcement.activeWideBan(device.getInstallIdHash()).isPresent()) {
                    // Banned for every account after its ticket was issued: never trusted, and
                    // never allowed to sign the number's other phones out.
                    log.warn("Login result for a handset banned on every account not applied msisdn={} device={}",
                            DeviceIdentity.logMask(t.getMsisdn()), device.getPublicId());
                    return;
                }
                if (device.getState() == DeviceState.PENDING_PIN) {
                    enforcement.bind(device, profile);
                }
                profile.setLastSignInAt(now);
                profile.setUpdatedAt(now);
                profiles.save(profile);
                if (t.getLat() != null && t.getLng() != null) {
                    locations.save(SignInLocation.builder().msisdn(t.getMsisdn()).deviceId(device.getPublicId())
                            .lat(t.getLat()).lng(t.getLng()).occurredAt(now).build());
                }
            }
            case LOCKED -> {
                // A correct OTP followed by wrong PINs until staging locked the PIN: someone
                // holds the SIM but not the PIN (§8.4). Staging's lock-out stays the
                // authority on the PIN itself; this blocks the DEVICE.
                if (t.isOtpVerified() || device.getState() == DeviceState.PENDING_PIN) {
                    metrics.fraudDeskAlert("sim_without_pin");
                    log.warn("FRAUD_DESK_ALERT sim_without_pin msisdn={} device={}",
                            DeviceIdentity.logMask(t.getMsisdn()), device.getPublicId());
                    if (properties.getEnforce().isBlocks()) {
                        enforcement.tempBlock(device, BlockReason.WRONG_PINS, null, DeviceEnforcement.Actor.broker(),
                                null, Map.of("otpVerified", t.isOtpVerified()));
                        return;
                    }
                }
                endPinGrace(device, now);
            }
            case WRONG_PIN, PIN_NOT_SET, ERROR -> {
                // Staging's own counter owns wrong PINs; DTX keeps the outcome as history
                // (the ticket row) and lets the PIN grace run, so a typo costs a retry, not
                // another SMS.
            }
        }
    }

    private void endPinGrace(CustomerDevice device, LocalDateTime now) {
        if (device.getState() == DeviceState.PENDING_PIN) {
            device.setPinGraceUntil(now);
            enforcement.refresh(device, true);
        }
    }

    // =====================================================================================
    // Broker: POST /device-security/broker/tickets/redeem (§3 rule 3)
    // =====================================================================================

    public TicketRedeemResponse redeem(TicketRedeemRequest req) {
        requireEnabled();
        if (!signer.isConfigured()) throw DeviceSecurityException.tryAgainShortly();
        SignInPurpose expected = req.purpose() == null ? SignInPurpose.SIGN_IN : SignInPurpose.valueOf(req.purpose());
        Claims claims;
        try {
            claims = signer.verifyTicket(req.ticket());
        } catch (LoginTicketSigner.TicketExpiredException e) {
            return rejected(null, null, "EXPIRED", req);
        } catch (IllegalArgumentException e) {
            return rejected(null, null, "INVALID", req);
        }
        String jti = claims.getId();
        String purpose = claims.get(LoginTicketSigner.CLAIM_PURPOSE, String.class);
        String msisdn = MsisdnValidator.normalizeToE164(req.msisdn(), deploymentCountry).orElse(req.msisdn());
        if (!msisdn.equals(claims.get(LoginTicketSigner.CLAIM_MSISDN, String.class))) {
            return rejected(jti, purpose, "MSISDN_MISMATCH", req);
        }
        String installId = claims.get(LoginTicketSigner.CLAIM_INSTALL_ID, String.class);
        if (installId == null || !installId.trim().equalsIgnoreCase(req.installId().trim())) {
            return rejected(jti, purpose, "DEVICE_MISMATCH", req);
        }
        if (!expected.name().equals(purpose)) {
            return rejected(jti, purpose, "PURPOSE_MISMATCH", req);
        }
        String failure = tx.execute(s -> {
            LocalDateTime now = now();
            TicketRef ref = tickets.findRef(jti).orElse(null);
            if (ref != null && !holderStillSignedIn(ref)) {
                // Signed out by another phone, blocked, or banned on every account since the
                // ticket was minted (a ticket minted just after a sign-out escapes its void).
                tickets.voidUnspent(jti, now);
                return SIGNED_OUT;
            }
            if (tickets.redeem(jti, now) == 1) {
                DeviceLoginTicket t = tickets.findById(jti).orElseThrow();
                events.record(SecurityEventType.TICKET_REDEEMED, ActorType.BROKER, null, t.getMsisdn(), null,
                        b -> b.installIdHash(t.getInstallIdHash()).purpose(purpose));
                return null;
            }
            return tickets.findById(jti).map(t -> t.getVoidedAt() != null ? "INVALID"
                    : t.getRedeemedAt() != null ? "ALREADY_USED" : "EXPIRED").orElse("INVALID");
        });
        if (SIGNED_OUT.equals(failure)) {
            // The broker sees INVALID, the reason it already handles; the log says why.
            return rejected(jti, purpose, "INVALID", req, "phone no longer signed in");
        }
        if (failure != null) {
            return rejected(jti, purpose, failure, req);
        }
        return new TicketRedeemResponse(true, jti, purpose, null);
    }

    /** Internal redeem outcome: the ticket's phone is no longer allowed to sign in. */
    private static final String SIGNED_OUT = "SIGNED_OUT";

    /**
     * Whether the phone a ticket was minted for may still complete its login. Its STATE is
     * read now — signed out by another phone (REVOKED, or NEW on its way back), stepped up,
     * stopped, or a handset banned on every account all refuse — but its trust window and
     * PIN grace are judged as they were when the ticket was minted, where every TOKEN leaves
     * them open: a PIN typed in the last seconds of the grace, or of a trust window, must
     * not cost the customer another code. A LOOKUP ticket names no phone and binds nothing.
     */
    private boolean holderStillSignedIn(TicketRef ref) {
        if (enforcement.activeWideBan(ref.installIdHash()).isPresent()) return false;
        if (ref.customerDeviceId() == null) return true;
        return devices.findById(ref.customerDeviceId())
                .map(d -> enforcement.signedIn(d, ref.issuedAt(), true)).orElse(false);
    }

    private TicketRedeemResponse rejected(String jti, String purpose, String reason, TicketRedeemRequest req) {
        return rejected(jti, purpose, reason, req, null);
    }

    private TicketRedeemResponse rejected(String jti, String purpose, String reason, TicketRedeemRequest req,
                                          String why) {
        tx.executeWithoutResult(s -> events.record(SecurityEventType.TICKET_REJECTED, ActorType.BROKER, null,
                MsisdnValidator.normalizeToE164(req.msisdn(), deploymentCountry).orElse(null), null,
                b -> b.installIdHash(DeviceIdentity.hashInstallId(req.installId())).reason(reason).purpose(purpose)
                        .note(jti == null ? null : "ticket " + jti + (why == null ? "" : ": " + why))));
        log.warn("Login ticket refused reason={}{} msisdn={}", reason, why == null ? "" : " (" + why + ")",
                DeviceIdentity.logMask(req.msisdn()));
        return new TicketRedeemResponse(false, jti, purpose, reason);
    }

    /** The broker's verification key (§16.3). */
    public Map<String, Object> jwks() {
        requireEnabled();
        return signer.jwks();
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    /**
     * Remembers the channel the customer chose. Bookkeeping after the code has already
     * gone out, so it never fails the send: under the number's lock (fresh read, no
     * version clash with a bind), and any database failure is logged, not thrown.
     */
    private void rememberChannel(OtpSendResponse sent) {
        try {
            tx.executeWithoutResult(s -> otpChallengeMsisdn(sent.challengeId()).ifPresent(msisdn -> {
                LocalDateTime now = now();
                CustomerSecurityProfile p = numberLock.lock(msisdn, now, NumberLock.CUSTOMER);
                p.setPreferredChannel(sent.channel());
                p.setUpdatedAt(now);
                profiles.save(p);
            }));
        } catch (DataAccessException | TransactionException e) {
            log.warn("Could not remember the OTP channel for challenge {}: {}", sent.challengeId(),
                    e.getClass().getSimpleName());
        }
    }

    private Optional<String> otpChallengeMsisdn(String challengeId) {
        return otp.msisdnOf(challengeId);
    }

    /** Refreshes what the device reports about itself, and where and when it was last seen. */
    private void touch(CustomerDevice device, ClientServiceRequest req, RequestMeta meta, LocalDateTime now, boolean facts) {
        DeviceFacts f = req.device();
        if (facts) {
            if (notBlank(f.platform())) device.setPlatform(trunc(f.platform().trim().toLowerCase(Locale.ROOT), 16));
            if (notBlank(f.osVersion())) device.setOsVersion(trunc(f.osVersion(), 32));
            if (notBlank(f.model())) device.setModel(trunc(f.model(), 64));
            if (notBlank(f.manufacturer())) device.setManufacturer(trunc(f.manufacturer(), 64));
            if (notBlank(f.appVersion())) device.setAppVersion(trunc(f.appVersion(), 32));
            device.setLabel(DeviceIdentity.fullLabel(device.getManufacturer(), device.getModel(), device.getPlatform(),
                    device.getOsVersion()));
        }
        var loc = req.location();
        if (loc != null && "GRANTED".equalsIgnoreCase(loc.status()) && loc.lat() != null && loc.lng() != null
                && !Boolean.TRUE.equals(loc.mocked())) {
            device.setLastSeenLat(GeoMath.round3(loc.lat()));
            device.setLastSeenLng(GeoMath.round3(loc.lng()));
            device.setLastSeenNear(gazetteer.nearest(device.getLastSeenLat(), device.getLastSeenLng()));
        }
        device.setLastSeenAt(now);
        device.setLastIp(trunc(meta.ip(), 64));
        device.setUpdatedAt(now);
        devices.save(device);
    }

    private void logDecision(Decided d, RiskEngine.Assessment a, CustomerDevice device, String msisdn,
                             SignInPurpose purpose, SignInContext context, ClientServiceRequest req, RequestMeta meta,
                             String overrideReason) {
        Decision returned = d.decision();
        Decision evaluated = a == null ? returned : a.decision();
        Map<String, Object> features = new LinkedHashMap<>();
        if (a != null) {
            features.put("signals", a.signals());
            features.put("verdict", a.verdict().name());
        }
        features.put("watchMode", a != null && returned != evaluated);
        if (device != null && DeviceEnforcement.REPLACED.equals(device.getStateReason())) {
            // A phone signed out by another phone of its number: lets the fraud desk tell
            // phone switches from other first sign-ins.
            features.put("replacedPhone", true);
        }
        if (req.integrity() != null) {
            features.put("integritySource", req.integrity().source());
            if (req.integrity().threats() != null && !req.integrity().threats().isEmpty()) {
                features.put("threats", req.integrity().threats());
            }
        }
        if (meta.appCheck() != null) features.put("appCheck", meta.appCheck());
        if (req.location() != null) {
            features.put("locationStatus", req.location().status());
            if (req.location().accuracyM() != null) features.put("accuracyM", req.location().accuracyM());
            if (Boolean.TRUE.equals(req.location().mocked())) features.put("mocked", true);
        }
        DeviceFacts f = req.device();
        features.put("appVersion", f.appVersion());
        features.put("runtimeVersion", f.runtimeVersion());
        features.put("updateId", f.updateId());
        String reason = overrideReason != null ? overrideReason
                : a != null && a.reasonCode() != null ? a.reasonCode() : d.reason();
        events.record(SecurityEventType.SIGN_IN_DECISION, ActorType.APP, null, msisdn, device, b -> b
                .decision(returned == null ? null : returned.name())
                .evaluatedDecision(evaluated == null ? null : evaluated.name())
                .reason(reason)
                .supportRef(d.supportRef())
                .purpose(purpose.name())
                .context(context.name())
                .requestId(DeviceSecurityEventLog.truncate(req.requestId(), 64))
                .ipAddress(trunc(meta.ip(), 64))
                .riskScore(a == null ? null : a.score())
                .features(events.json(features)));
        if (returned != null) {
            metrics.decision(returned, evaluated == null ? returned : evaluated, purpose, context);
        }
    }

    private void logLookup(Decided d, String msisdn, String installHash, ClientServiceRequest req, RequestMeta meta,
                           String reason) {
        events.record(SecurityEventType.SIGN_IN_DECISION, ActorType.APP, null, msisdn, null, b -> b
                .installIdHash(installHash)
                .decision(d.decision() == null ? null : d.decision().name())
                .evaluatedDecision(d.decision() == null ? null : d.decision().name())
                .reason(reason != null ? reason : d.reason())
                .supportRef(d.supportRef())
                .purpose(SignInPurpose.LOOKUP.name())
                .context(req.context() == null ? SignInContext.SIGN_IN.name() : req.context())
                .requestId(DeviceSecurityEventLog.truncate(req.requestId(), 64))
                .ipAddress(trunc(meta.ip(), 64)));
        if (d.decision() != null) {
            metrics.decision(d.decision(), d.decision(), SignInPurpose.LOOKUP,
                    req.context() == null ? SignInContext.SIGN_IN : SignInContext.valueOf(req.context()));
        }
    }

    CustomerSecurityProfile profile(String msisdn, LocalDateTime now) {
        profiles.insertIfAbsent(msisdn, now);
        return profiles.findById(msisdn).orElseThrow();
    }

    private String normalise(String raw, String field) {
        return MsisdnValidator.normalizeToE164(raw, deploymentCountry)
                .orElseThrow(() -> DeviceSecurityException.badRequest(field, "Enter a valid mobile number."));
    }

    static String requireHeaderInstallId(RequestMeta meta) {
        if (meta.headerInstallId() == null || meta.headerInstallId().isBlank()) {
            throw DeviceSecurityException.badRequest("x-device-id", "The x-device-id header is required.");
        }
        if (meta.headerInstallId().length() > 64) {
            throw DeviceSecurityException.badRequest("x-device-id", "The x-device-id header is not valid.");
        }
        return meta.headerInstallId().trim();
    }

    static DeviceSecurityException rateLimited(long retryAfterSeconds) {
        long minutes = Math.max(1, (retryAfterSeconds + 59) / 60);
        return new DeviceSecurityException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "Too many attempts. Please try again in " + minutes + (minutes == 1 ? " minute." : " minutes."),
                null, retryAfterSeconds);
    }

    public void requireEnabled() {
        if (!properties.isEnabled()) throw DeviceSecurityException.disabled();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trunc(String s, int max) {
        return DeviceSecurityEventLog.truncate(s == null ? null : s.trim(), max);
    }
}
