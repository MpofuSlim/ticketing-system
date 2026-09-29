package com.innbucks.userservice.devicesecurity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.ActionResult;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.BanRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.BlockRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.CountersView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.CustomerOverview;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.EventView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.FraudFlagRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.PageView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.ProfileView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.SupportDeviceView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.SupportRefLookup;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.WideBanView;
import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import com.innbucks.userservice.devicesecurity.entity.CustomerSecurityProfile;
import com.innbucks.userservice.devicesecurity.entity.DeviceSecurityEvent;
import com.innbucks.userservice.devicesecurity.entity.DeviceWideBan;
import com.innbucks.userservice.devicesecurity.repository.CustomerDeviceRepository;
import com.innbucks.userservice.devicesecurity.repository.CustomerSecurityProfileRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceOtpChallengeRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceSecurityEventRepository;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.util.MsisdnMasking;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The call center's and fraud desk's tools ({@code /admin/device-security}):
 * find a customer's phones by number or by the reference they read out, see
 * why a phone is stopped and what to do about it, and block, unlock, remove,
 * reset or ban — with every change sealed on the audit chain under the agent's
 * identity and the customer told by SMS/WhatsApp.
 *
 * <p>Two tiers of authority, enforced here as well as by {@code @PreAuthorize}:
 * {@code device-security:manage} covers the everyday call-center actions; lifting
 * a ban that only the fraud desk may lift (SHARED_DEVICE, CONFIRMED_FRAUD,
 * SIM_SWAP, or any device-wide ban) additionally needs
 * {@code device-security:fraud} — otherwise the call center could undo the fraud
 * desk's decision on a caller's say-so, which is exactly the social-engineering
 * path a fraudster would take.
 */
@Service
@Slf4j
public class DeviceSupportService {

    private static final Set<BanReason> FRAUD_DESK_ONLY = EnumSet.of(BanReason.SHARED_DEVICE,
            BanReason.CONFIRMED_FRAUD, BanReason.SIM_SWAP);

    private final CustomerDeviceRepository devices;
    private final CustomerSecurityProfileRepository profiles;
    private final DeviceOtpChallengeRepository challenges;
    private final DeviceSecurityEventRepository eventRepository;
    private final DeviceEnforcement enforcement;
    private final DeviceOtpService otp;
    private final DeviceSecurityEventLog events;
    private final DeviceSecurityMessages messages;
    private final AuditService auditService;
    private final DeviceSecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String deploymentCountry;

    public DeviceSupportService(CustomerDeviceRepository devices, CustomerSecurityProfileRepository profiles,
                                DeviceOtpChallengeRepository challenges, DeviceSecurityEventRepository eventRepository,
                                DeviceEnforcement enforcement, DeviceOtpService otp, DeviceSecurityEventLog events,
                                DeviceSecurityMessages messages, AuditService auditService,
                                DeviceSecurityProperties properties, ObjectMapper objectMapper,
                                Clock deviceSecurityClock, @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.devices = devices;
        this.profiles = profiles;
        this.challenges = challenges;
        this.eventRepository = eventRepository;
        this.enforcement = enforcement;
        this.otp = otp;
        this.events = events;
        this.messages = messages;
        this.auditService = auditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = deviceSecurityClock;
        this.deploymentCountry = deploymentCountry;
    }

    // ---- reads -------------------------------------------------------------------------

    @Transactional
    public CustomerOverview overview(String rawMsisdn) {
        String msisdn = normalise(rawMsisdn);
        LocalDateTime now = now();
        List<SupportDeviceView> views = new ArrayList<>();
        for (CustomerDevice d : devices.findByMsisdnOrderByLastSeenAtDesc(msisdn)) {
            enforcement.refresh(d, false);
            views.add(view(d, now));
        }
        CustomerSecurityProfile p = profiles.findById(msisdn).orElse(null);
        ProfileView profile = p == null ? new ProfileView(null, false, null, null, null, null)
                : new ProfileView(p.getPreferredChannel() == null ? null : p.getPreferredChannel().name(),
                p.fraudFlagged(), p.getFraudFlaggedAt(), p.getFraudFlagNote(), p.getPinIssuedAt(), p.getLastSignInAt());
        long open = challenges.findByMsisdnAndStatus(msisdn, ChallengeStatus.OPEN).stream()
                .filter(c -> c.liveAt(now)).count();
        CountersView counters = new CountersView(
                challenges.countByMsisdnAndCreatedAtAfter(msisdn, now.minusHours(1)),
                challenges.countByMsisdnAndCreatedAtAfter(msisdn, now.minusDays(1)),
                eventRepository.countByMsisdnAndActorTypeAndEventTypeInAndOccurredAtAfter(msisdn, ActorType.USSD.name(),
                        List.of(SecurityEventType.DEVICE_UNLOCKED.name(), SecurityEventType.USSD_UNLOCK_REFUSED.name()),
                        now.minusDays(1)),
                open);
        List<EventView> recent = eventRepository.findByMsisdnOrderByOccurredAtDescIdDesc(msisdn, PageRequest.of(0, 20))
                .map(this::event).getContent();
        return new CustomerOverview(msisdn, profile, views, counters, recent, summary(views, now));
    }

    @Transactional(readOnly = true)
    public PageView<EventView> events(String rawMsisdn, int page, int size, List<String> types) {
        String msisdn = normalise(rawMsisdn);
        PageRequest pr = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<DeviceSecurityEvent> found = types == null || types.isEmpty()
                ? eventRepository.findByMsisdnOrderByOccurredAtDescIdDesc(msisdn, pr)
                : eventRepository.findByMsisdnAndEventTypeInOrderByOccurredAtDescIdDesc(msisdn, types, pr);
        return page(found.map(this::event));
    }

    @Transactional
    public SupportRefLookup bySupportRef(String typed) {
        String ref = SupportRefs.normalise(typed);
        List<DeviceSecurityEvent> found = eventRepository.findBySupportRefOrderByOccurredAtAscIdAsc(ref);
        if (found.isEmpty()) {
            throw new DeviceSecurityException(HttpStatus.NOT_FOUND, "support_ref_not_found",
                    "No block, ban or unlock carries reference " + ref + ". Check the spelling with the caller.");
        }
        DeviceSecurityEvent first = found.get(0);
        LocalDateTime now = now();
        SupportDeviceView device = first.getDeviceId() == null ? null
                : devices.findByPublicId(first.getDeviceId()).map(d -> view(enforcement.refresh(d, false), now)).orElse(null);
        return new SupportRefLookup(ref, first.getMsisdn(), device, found.stream().map(this::event).toList());
    }

    @Transactional(readOnly = true)
    public PageView<SupportDeviceView> stopped(String state, int page, int size) {
        Set<DeviceState> states;
        if (state == null || state.isBlank()) {
            states = EnumSet.of(DeviceState.TEMP_BLOCKED, DeviceState.BANNED);
        } else if ("TEMP_BLOCKED".equals(state) || "BANNED".equals(state)) {
            states = EnumSet.of(DeviceState.valueOf(state));
        } else {
            throw DeviceSecurityException.badRequest("state", "state must be TEMP_BLOCKED or BANNED.");
        }
        LocalDateTime now = now();
        return page(devices.findByStateInOrderByStateChangedAtDesc(states,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100))).map(d -> view(d, now)));
    }

    /** Every account the physical phone behind this device is paired with — the mule-farm view. */
    @Transactional(readOnly = true)
    public List<SupportDeviceView> sameHandset(UUID deviceId) {
        CustomerDevice d = device(deviceId);
        LocalDateTime now = now();
        return devices.findByInstallIdHash(d.getInstallIdHash()).stream().map(x -> view(x, now)).toList();
    }

    // ---- actions -----------------------------------------------------------------------

    @Transactional
    public ActionResult block(UUID deviceId, BlockRequest req, String agent, AuditContext ctx) {
        CustomerDevice d = device(deviceId);
        DeviceEnforcement.Actor actor = actor(agent, ctx);
        if ("UNTIL_UNLOCKED".equals(req.mode())) {
            if (d.getState() == DeviceState.BANNED) throw alreadyBanned(d);
            enforcement.ban(d, BanReason.CUSTOMER_REPORTED, actor, req.note(), null);
            return result(d, "The phone is blocked from this account until the customer unlocks it on *569# "
                    + "(or support lifts it). The customer has been sent an SMS with reference " + d.getSupportRef() + ".");
        }
        if (req.durationMinutes() == null) {
            throw DeviceSecurityException.badRequest("durationMinutes", "durationMinutes is required for a TEMPORARY block.");
        }
        if (d.getState() == DeviceState.BANNED) throw alreadyBanned(d);
        Duration duration = Duration.ofMinutes(req.durationMinutes());
        DeviceSecurityProperties.Blocks limits = properties.getBlocks();
        if (duration.compareTo(limits.getSupportMin()) < 0 || duration.compareTo(limits.getSupportMax()) > 0) {
            throw DeviceSecurityException.badRequest("durationMinutes", "durationMinutes must be between "
                    + limits.getSupportMin().toMinutes() + " and " + limits.getSupportMax().toMinutes() + ".");
        }
        enforcement.tempBlock(d, BlockReason.SUPPORT, duration, actor, req.note(), null);
        return result(d, "Sign-in on this phone is paused until " + messages.time(d.getBlockedUntil(), now(), false)
                + " and lifts on its own. The customer has been sent an SMS.");
    }

    @Transactional
    public ActionResult ban(UUID deviceId, BanRequest req, String agent, AuditContext ctx) {
        CustomerDevice d = device(deviceId);
        BanReason reason = BanReason.valueOf(req.reason());
        enforcement.ban(d, reason, actor(agent, ctx), req.note(), null);
        String next = reason.deviceWide()
                ? "The phone is now blocked for EVERY account on it. Only the fraud desk can lift this."
                : "The phone is blocked from this account. The customer can unlock it on *569# after 24 hours.";
        return result(d, next + " Reference " + d.getSupportRef() + ".");
    }

    @Transactional
    public ActionResult unlock(UUID deviceId, String note, String agent, AuditContext ctx, boolean fraudAuthority) {
        CustomerDevice d = device(deviceId);
        enforcement.refresh(d, false);
        Optional<DeviceWideBan> wide = enforcement.activeWideBan(d.getInstallIdHash());
        if (!d.getState().isStopped() && wide.isEmpty()) {
            throw new DeviceSecurityException(HttpStatus.CONFLICT, "device_not_blocked",
                    "This phone isn't blocked, so there is nothing to unlock.");
        }
        BanReason banReason = d.getState() == DeviceState.BANNED ? UssdDeviceService.banReason(d.getStateReason()) : null;
        boolean fraudDeskOnly = wide.isPresent() || (banReason != null && FRAUD_DESK_ONLY.contains(banReason));
        if (fraudDeskOnly && !fraudAuthority) {
            throw new DeviceSecurityException(HttpStatus.FORBIDDEN, "fraud_desk_required",
                    "Only the fraud desk can lift this block. Escalate with reference "
                            + (wide.isPresent() ? wide.get().getSupportRef() : d.getSupportRef()) + ".");
        }
        enforcement.unlock(d, actor(agent, ctx), note);
        return result(d, "Unlocked. The next sign-in on this phone will ask for a code, then the PIN. "
                + "The customer has been sent an SMS.");
    }

    @Transactional
    public ActionResult revoke(UUID deviceId, String note, String agent, AuditContext ctx) {
        CustomerDevice d = device(deviceId);
        if (d.getState() == DeviceState.REVOKED) {
            throw new DeviceSecurityException(HttpStatus.CONFLICT, "device_already_removed",
                    "This phone has already been removed from the account.");
        }
        if (d.getState() == DeviceState.BANNED) {
            throw new DeviceSecurityException(HttpStatus.CONFLICT, "device_blocked",
                    "This phone is blocked. Removing it would let it start again as a new phone — unlock it first if "
                            + "that is really what the customer needs.");
        }
        enforcement.revoke(d, actor(agent, ctx), note);
        return result(d, "Removed. The phone is signed out within 15 minutes; signing in on it again needs a code.");
    }

    @Transactional
    public ActionResult resetTrust(UUID deviceId, String note, String agent, AuditContext ctx) {
        CustomerDevice d = device(deviceId);
        if (d.getState() == DeviceState.BANNED) {
            throw alreadyBanned(d);
        }
        enforcement.resetTrust(d, actor(agent, ctx), note);
        return result(d, "Reset. The next sign-in on this phone is treated as a brand-new phone (code, then PIN).");
    }

    @Transactional
    public ProfileView setFraudFlag(String rawMsisdn, FraudFlagRequest req, String agent, AuditContext ctx) {
        String msisdn = normalise(rawMsisdn);
        LocalDateTime now = now();
        profiles.insertIfAbsent(msisdn, now);
        CustomerSecurityProfile p = profiles.findById(msisdn).orElseThrow();
        boolean flag = Boolean.TRUE.equals(req.flagged());
        p.setFraudFlaggedAt(flag ? now : null);
        p.setFraudFlagNote(flag ? DeviceSecurityEventLog.truncate(req.note(), 1000) : null);
        p.setUpdatedAt(now);
        profiles.save(p);
        if (flag) {
            // Shorten live trust immediately rather than at the next bind (§8.3: 30 days after a fraud flag).
            LocalDateTime cap = now.plusDays(properties.getTrust().getFraudFlagDays());
            for (CustomerDevice d : devices.findByMsisdnOrderByLastSeenAtDesc(msisdn)) {
                if (d.getTrustedUntil() != null && d.getTrustedUntil().isAfter(cap)) {
                    d.setTrustedUntil(cap);
                    d.setUpdatedAt(now);
                    devices.save(d);
                }
            }
        }
        events.record(flag ? SecurityEventType.FRAUD_FLAG_SET : SecurityEventType.FRAUD_FLAG_CLEARED,
                ActorType.SUPPORT, agent, msisdn, null, b -> b.note(DeviceSecurityEventLog.truncate(req.note(), 1000)));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("msisdn", MsisdnMasking.mask(msisdn));
        meta.put("flagged", flag);
        meta.put("note", DeviceSecurityEventLog.truncate(req.note(), 500));
        auditService.recordSuccess(AuditEventType.DEVICE_SECURITY_FRAUD_FLAG_CHANGED, agent,
                AuditService.ACTOR_TYPE_USER, "msisdn:" + MsisdnMasking.mask(msisdn), "CUSTOMER", meta, ctx);
        return new ProfileView(p.getPreferredChannel() == null ? null : p.getPreferredChannel().name(), p.fraudFlagged(),
                p.getFraudFlaggedAt(), p.getFraudFlagNote(), p.getPinIssuedAt(), p.getLastSignInAt());
    }

    /** Kills every open code for a number — "I got a code I didn't ask for". */
    @Transactional
    public int voidCodes(String rawMsisdn, String note, String agent, AuditContext ctx) {
        String msisdn = normalise(rawMsisdn);
        int voided = otp.voidForNumber(msisdn);
        events.record(SecurityEventType.OTP_VOIDED, ActorType.SUPPORT, agent, msisdn, null,
                b -> b.note(DeviceSecurityEventLog.truncate(note, 1000)).reason(String.valueOf(voided)));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("msisdn", MsisdnMasking.mask(msisdn));
        meta.put("voided", voided);
        meta.put("note", DeviceSecurityEventLog.truncate(note, 500));
        auditService.recordSuccess(AuditEventType.DEVICE_SECURITY_OTP_VOIDED, agent, AuditService.ACTOR_TYPE_USER,
                "msisdn:" + MsisdnMasking.mask(msisdn), "CUSTOMER", meta, ctx);
        return voided;
    }

    // ---- views -------------------------------------------------------------------------

    SupportDeviceView view(CustomerDevice d, LocalDateTime now) {
        Optional<DeviceWideBan> wide = enforcement.activeWideBan(d.getInstallIdHash());
        WideBanView wideView = wide.map(w -> new WideBanView(w.getReason(), w.getSupportRef(), w.getBannedAt(),
                w.getBannedBy())).orElse(null);
        long others = devices.countOtherBoundCustomers(d.getInstallIdHash(), d.getMsisdn());
        return new SupportDeviceView(d.getPublicId(), d.getMsisdn(),
                d.getLabel() == null ? DeviceEnforcement.shortLabel(d) : d.getLabel(),
                d.getPlatform(), d.getOsVersion(), d.getModel(), d.getManufacturer(), d.getAppVersion(),
                d.getState().name(), d.getStateReason(), d.getBlockedAt(), d.getBlockedUntil(), d.getSupportRef(),
                d.isUssdUnlockable(), d.getUnlockableAfter(), d.getTrustedUntil(), d.getBoundAt(), d.getCoolingUntil(),
                d.getOtpVerifiedAt(),
                d.getFirstSeenAt(), d.getLastSeenAt(), d.getLastSeenNear(), d.getLastIp(), d.getLastUnlockedAt(),
                wideView, others, guidance(d, wide.orElse(null), others, now));
    }

    /** What the agent should say or do, in one or two sentences. */
    String guidance(CustomerDevice d, DeviceWideBan wide, long others, LocalDateTime now) {
        if (wide != null) {
            return "Blocked on EVERY account on this phone (" + wide.getReason() + "). Fraud desk only — escalate with "
                    + "reference " + wide.getSupportRef() + ". Do not unlock on the caller's word.";
        }
        String until = d.getBlockedUntil() == null ? null : messages.time(d.getBlockedUntil(), now, false);
        String sharedNote = others > 0 ? " This phone is also paired with " + others + " other account"
                + (others == 1 ? "" : "s") + "." : "";
        return switch (d.getState()) {
            case TRUSTED -> (d.trustedAt(now) ? "Signed in normally; trusted until "
                    + messages.time(d.getTrustedUntil(), now, false) + ". Nothing to do."
                    : "Trust has expired; the next sign-in asks for a code. Nothing to do.")
                    + (d.possessionVerified() ? "" : " Trusted while DTX was only watching: never confirmed with a "
                            + "code, so it will be asked for one once codes are switched on.")
                    + (d.coolingAt(now) ? " New phone: lower limits until " + messages.time(d.getCoolingUntil(), now, false) + "." : "")
                    + sharedNote;
            case NEW -> "This phone tried to sign in but was never confirmed. If the customer doesn't recognise it, "
                    + "block it (UNTIL_UNLOCKED)." + sharedNote;
            case PENDING_PIN -> "The code was confirmed; waiting for the PIN. If the customer has forgotten the PIN, "
                    + "direct them to PIN reset in the app." + sharedNote;
            case STEP_UP -> "The next sign-in on this phone asks for a code"
                    + (OtpReason.UNLOCKED.name().equals(d.getStateReason()) ? " (it was just unlocked)" : "")
                    + ". If codes aren't arriving, check the counters and try the other channel." + sharedNote;
            case TEMP_BLOCKED -> tempBlockGuidance(d, until);
            case BANNED -> banGuidance(d, now);
            case REVOKED -> "Removed from the account. Signing in on it again starts as a new phone, with a code.";
        };
    }

    private String tempBlockGuidance(CustomerDevice d, String until) {
        String reason = d.getStateReason() == null ? "" : d.getStateReason();
        String lifts = until == null ? "" : " until " + until + " and lifts on its own";
        return switch (reason) {
            case "OTP_ATTEMPTS" -> "Paused" + lifts + " after too many wrong codes. You may unlock it now if the caller "
                    + "passes verification; the next sign-in still asks for a code.";
            case "VELOCITY" -> "Paused" + lifts + " after an unusual number of code requests — possibly someone else "
                    + "trying this number. Verify the caller carefully before unlocking.";
            case "WRONG_PINS" -> "Paused" + lifts + ": a correct code was followed by wrong PINs until the PIN locked. "
                    + "Someone may hold the SIM but not the PIN. Verify carefully; suggest a PIN reset.";
            case "RISK" -> "Paused" + lifts + " because several risk signals fired at once (for example a new phone in "
                    + "an unusual place). Verify before unlocking.";
            case "INTEGRITY_HOLD" -> "The phone's security check fails (rooted or jailbroken). The hold lifts at the "
                    + "first sign-in where the check passes; unlocking won't help while the phone is rooted.";
            case "SUPPORT" -> "Paused by support" + lifts + ".";
            default -> "Paused" + lifts + ".";
        };
    }

    private String banGuidance(CustomerDevice d, LocalDateTime now) {
        BanReason reason = UssdDeviceService.banReason(d.getStateReason());
        String ussd = d.isUssdUnlockable()
                ? (d.getUnlockableAfter() != null && d.getUnlockableAfter().isAfter(now)
                ? " The customer can unlock it on *569# from " + messages.time(d.getUnlockableAfter(), now, false) + "."
                : " The customer can unlock it themselves on *569# (Unlock device).")
                : " Not unlockable on *569#.";
        if (reason == null) return "Blocked." + ussd;
        return switch (reason) {
            case INTEGRITY -> "Blocked: the app detected hooking tools, a tampered app, automation or malware." + ussd
                    + " Once unlocked, a dirty phone is blocked again at its next sign-in.";
            case FRAUD_SUSPECTED -> "Blocked on suspicion of fraud (fraud desk, or repeated pauses)." + ussd;
            case SHARED_DEVICE -> "Blocked: this phone signed in to too many accounts (mule pattern). Fraud desk only.";
            case CONFIRMED_FRAUD -> "Blocked for confirmed fraud. Fraud desk only.";
            case SIM_SWAP -> "Blocked after a recent SIM swap. Fraud desk only.";
            case CUSTOMER_REPORTED -> "The customer blocked it (lost or stolen)." + ussd
                    + " You may unlock it after verifying the caller.";
        };
    }

    EventView event(DeviceSecurityEvent e) {
        return new EventView(e.getId(), e.getOccurredAt(), e.getEventType(), describe(e), e.getDecision(),
                e.getEvaluatedDecision(), e.getReason(), e.getSupportRef(), e.getActorType(), e.getActorId(),
                e.getChannel(), e.getPurpose(), e.getContext(), e.getDeviceId(), e.getUssdSessionId(), e.getIpAddress(),
                e.getRiskScore(), features(e.getFeatures()), e.getNote());
    }

    static String describe(DeviceSecurityEvent e) {
        SecurityEventType type;
        try {
            type = SecurityEventType.valueOf(e.getEventType());
        } catch (IllegalArgumentException ex) {
            return e.getEventType();
        }
        String reason = e.getReason() == null ? "" : " (" + e.getReason() + ")";
        return switch (type) {
            case SIGN_IN_DECISION -> "Sign-in check: " + (e.getDecision() == null ? "refused" : e.getDecision())
                    + (e.getEvaluatedDecision() != null && !e.getEvaluatedDecision().equals(e.getDecision())
                    ? " (would have been " + e.getEvaluatedDecision() + " — watch mode)" : "") + reason;
            case OTP_CHALLENGE_CREATED -> "Code requested" + reason;
            case OTP_SENT -> "Code sent by " + e.getChannel();
            case OTP_SEND_FAILED -> "Code could not be sent by " + e.getChannel();
            case OTP_VERIFIED -> "Correct code entered";
            case OTP_WRONG -> "Wrong code entered";
            case OTP_CHALLENGE_DEAD -> "Out of code attempts";
            case OTP_RELAY_SUSPECTED -> "Code typed on a different phone than the one that asked for it";
            case OTP_VOIDED -> "Open codes cancelled by support";
            case TICKET_REDEEMED -> "Login ticket used";
            case TICKET_REJECTED -> "Login ticket refused" + reason;
            case LOGIN_RESULT -> "PIN login: " + e.getReason();
            case DEVICE_BOUND -> "Phone confirmed (trusted)";
            case DEVICE_TEMP_BLOCKED -> "Phone paused" + reason;
            case DEVICE_BANNED -> "Phone blocked" + reason;
            case DEVICE_UNLOCKED -> "Phone unlocked by " + e.getActorType();
            case DEVICE_REVOKED -> "Phone removed by " + e.getActorType();
            case DEVICE_TRUST_RESET -> "Phone reset to new by support";
            case BLOCK_LIFTED -> "Pause ended on its own" + reason;
            case INTEGRITY_HOLD_LIFTED -> "Security-check hold lifted";
            case USSD_UNLOCK_REFUSED -> "*569# unlock refused" + reason;
            case USSD_BLOCK_REFUSED -> "*569# block refused" + reason;
            case SESSION_STEP_UP_NOT_REQUIRED -> "In-app check not needed" + reason;
            case SESSION_STEP_UP_VERIFIED -> "In-app check passed" + reason;
            case FRAUD_FLAG_SET -> "Fraud flag set by support";
            case FRAUD_FLAG_CLEARED -> "Fraud flag cleared by support";
            case NOTIFICATION_SENT -> "Message sent";
            case NOTIFICATION_FAILED -> "Message failed";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> features(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("_raw", json);
        }
    }

    private String summary(List<SupportDeviceView> views, LocalDateTime now) {
        long signedIn = views.stream().filter(v -> DeviceState.TRUSTED.name().equals(v.state())).count();
        List<String> parts = new ArrayList<>();
        parts.add(signedIn + (signedIn == 1 ? " phone" : " phones") + " signed in");
        for (SupportDeviceView v : views) {
            if (v.deviceWideBan() != null) {
                parts.add(v.label() + " blocked on every account (" + v.deviceWideBan().supportRef() + ")");
            } else if (DeviceState.TEMP_BLOCKED.name().equals(v.state())) {
                parts.add(v.label() + " paused" + (v.blockedUntil() == null ? "" : " until "
                        + messages.time(v.blockedUntil(), now, false)) + " (" + v.supportRef() + ")");
            } else if (DeviceState.BANNED.name().equals(v.state())) {
                parts.add(v.label() + " blocked (" + v.supportRef() + ")");
            }
        }
        return views.isEmpty() ? "No phones have tried to sign in with this number." : String.join("; ", parts) + ".";
    }

    // ---- helpers -----------------------------------------------------------------------

    private ActionResult result(CustomerDevice d, String next) {
        return new ActionResult(view(d, now()), next);
    }

    private CustomerDevice device(UUID deviceId) {
        return devices.findByPublicId(deviceId).orElseThrow(() -> new DeviceSecurityException(HttpStatus.NOT_FOUND,
                "device_not_found", "No phone with that deviceId."));
    }

    private static DeviceSecurityException alreadyBanned(CustomerDevice d) {
        return new DeviceSecurityException(HttpStatus.CONFLICT, "device_already_blocked",
                "This phone is already blocked (reference " + d.getSupportRef() + ").");
    }

    private static DeviceEnforcement.Actor actor(String agent, AuditContext ctx) {
        return new DeviceEnforcement.Actor(ActorType.SUPPORT, agent, null, ctx);
    }

    private static <T> PageView<T> page(Page<T> p) {
        return new PageView<>(p.getContent(), p.getTotalElements(), p.getTotalPages(), p.getNumber(), p.getSize());
    }

    private String normalise(String raw) {
        return MsisdnValidator.normalizeToE164(raw, deploymentCountry)
                .orElseThrow(() -> DeviceSecurityException.badRequest("msisdn", "That isn't a valid mobile number."));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
