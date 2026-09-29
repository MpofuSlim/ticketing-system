package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import com.innbucks.userservice.devicesecurity.entity.CustomerSecurityProfile;
import com.innbucks.userservice.devicesecurity.entity.DeviceWideBan;
import com.innbucks.userservice.devicesecurity.repository.CustomerDeviceRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceLoginTicketRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceOtpChallengeRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceSecurityEventRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceWideBanRepository;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every state change that stops, frees, forgets or trusts a device — shared by
 * the risk engine, the broker's login results, *569# and the admin portal, so a
 * block placed by any of them behaves identically: open codes die, unspent
 * tickets die, the customer is told, the decision log says why, and when a
 * PERSON made the change it is also sealed on the tamper-evident audit chain.
 *
 * <p>All methods join the caller's transaction.
 */
@Component
@Slf4j
public class DeviceEnforcement {

    /** Where a temporary block or ban came from, for the log, the chain and the notice. */
    public record Actor(ActorType type, String id, String ussdSessionId, AuditContext auditContext) {
        public static Actor system() {
            return new Actor(ActorType.SYSTEM, null, null, AuditContext.none());
        }

        public static Actor broker() {
            return new Actor(ActorType.BROKER, null, null, AuditContext.none());
        }

        boolean person() {
            return type == ActorType.SUPPORT || type == ActorType.USSD || type == ActorType.CUSTOMER;
        }
    }

    private final CustomerDeviceRepository devices;
    private final DeviceWideBanRepository wideBans;
    private final DeviceOtpChallengeRepository challenges;
    private final DeviceLoginTicketRepository tickets;
    private final DeviceSecurityEventRepository eventRepository;
    private final DeviceSecurityEventLog events;
    private final DeviceSecurityMetrics metrics;
    private final AuditService auditService;
    private final ApplicationEventPublisher publisher;
    private final DeviceSecurityProperties properties;
    private final Clock clock;

    public DeviceEnforcement(CustomerDeviceRepository devices, DeviceWideBanRepository wideBans,
                             DeviceOtpChallengeRepository challenges, DeviceLoginTicketRepository tickets,
                             DeviceSecurityEventRepository eventRepository, DeviceSecurityEventLog events,
                             DeviceSecurityMetrics metrics, AuditService auditService,
                             ApplicationEventPublisher publisher, DeviceSecurityProperties properties,
                             Clock deviceSecurityClock) {
        this.devices = devices;
        this.wideBans = wideBans;
        this.challenges = challenges;
        this.tickets = tickets;
        this.eventRepository = eventRepository;
        this.events = events;
        this.metrics = metrics;
        this.auditService = auditService;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = deviceSecurityClock;
    }

    // ---- temporary blocks (§8.4) ----------------------------------------------------

    /**
     * Places the next step of the ladder (15 min → 1 h → 24 h over 30 days). The
     * Nth block inside the window becomes a FRAUD_SUSPECTED ban instead, when bans
     * are enforced. {@code explicitDuration} is for the call center; an integrity
     * hold has no end time at all.
     */
    @Transactional
    public CustomerDevice tempBlock(CustomerDevice d, BlockReason reason, Duration explicitDuration,
                                    Actor actor, String note, Map<String, ?> features) {
        LocalDateTime now = now();
        DeviceSecurityProperties.Blocks blocks = properties.getBlocks();
        LocalDateTime until;
        if (reason == BlockReason.INTEGRITY_HOLD) {
            until = null;
        } else if (explicitDuration != null) {
            until = now.plus(explicitDuration);
        } else {
            long prior = eventRepository.countByMsisdnAndInstallIdHashAndEventTypeAndReasonNotAndOccurredAtAfter(
                    d.getMsisdn(), d.getInstallIdHash(), SecurityEventType.DEVICE_TEMP_BLOCKED.name(),
                    BlockReason.SUPPORT.name(), now.minusDays(blocks.getWindowDays()));
            if (prior + 1 >= blocks.getBanOnBlockNumber() && properties.getEnforce().isBans()) {
                return ban(d, BanReason.FRAUD_SUSPECTED, actor,
                        "Block number " + (prior + 1) + " in " + blocks.getWindowDays() + " days", features);
            }
            List<Duration> ladder = blocks.getLadder();
            until = now.plus(ladder.get((int) Math.min(prior, ladder.size() - 1)));
        }
        String ref = SupportRefs.next();
        d.transition(DeviceState.TEMP_BLOCKED, reason.name(), who(actor), now);
        d.setBlockedAt(now);
        d.setBlockedUntil(until);
        // A temporary block can be lifted on *569# (SIM + PIN), except an integrity
        // hold, which only a clean security check lifts.
        d.setUssdUnlockable(reason != BlockReason.INTEGRITY_HOLD);
        d.setUnlockableAfter(null);
        d.setSupportRef(ref);
        d.setPinGraceUntil(null);
        devices.save(d);
        cutOff(d, now);
        events.record(SecurityEventType.DEVICE_TEMP_BLOCKED, actor.type(), actor.id(), d.getMsisdn(), d,
                b -> b.reason(reason.name()).supportRef(ref).decision(Decision.TEMP_BLOCKED.name())
                        .ussdSessionId(actor.ussdSessionId()).note(DeviceSecurityEventLog.truncate(note, 1000))
                        .features(events.json(features)));
        metrics.enforcement("temp_block", reason.name(), true, actor.type().name());
        seal(AuditEventType.DEVICE_SECURITY_BLOCKED, actor, d, reason.name(), ref, note,
                until == null ? null : Map.of("blockedUntil", until.toString()));
        publisher.publishEvent(new DeviceNotice(
                reason == BlockReason.INTEGRITY_HOLD ? DeviceNotice.Type.INTEGRITY_HOLD : DeviceNotice.Type.TEMP_BLOCKED,
                d.getMsisdn(), shortLabel(d), now, until, ref, null, actor.type() == ActorType.USSD));
        log.info("Device temp-blocked msisdn={} device={} reason={} until={} ref={}",
                DeviceIdentity.logMask(d.getMsisdn()), d.getPublicId(), reason, until, ref);
        return d;
    }

    // ---- bans (§8.5) ------------------------------------------------------------------

    @Transactional
    public CustomerDevice ban(CustomerDevice d, BanReason reason, Actor actor, String note, Map<String, ?> features) {
        LocalDateTime now = now();
        String ref = SupportRefs.next();
        // "A device banned again inside 7 days of an unlock goes to support only" (§9.3).
        boolean recentlyUnlocked = d.getLastUnlockedAt() != null
                && d.getLastUnlockedAt().isAfter(now.minus(properties.getUssd().getUnlockCooldown()));
        boolean ussdUnlockable = reason.ussdUnlockable() && !recentlyUnlocked;

        d.transition(DeviceState.BANNED, reason.name(), who(actor), now);
        d.setBlockedAt(now);
        d.setBlockedUntil(null);
        d.setUssdUnlockable(ussdUnlockable);
        // A fraud-desk suspicion is USSD-unlockable only after 24 hours (§8.5).
        d.setUnlockableAfter(reason == BanReason.FRAUD_SUSPECTED && actor.type() == ActorType.SUPPORT
                ? now.plus(properties.getUssd().getFraudSuspectedUnlockDelay()) : null);
        d.setSupportRef(ref);
        d.setTrustedUntil(null);
        d.setPinGraceUntil(null);
        devices.save(d);
        cutOff(d, now);

        if (reason.deviceWide()) {
            DeviceWideBan wide = wideBans.findById(d.getInstallIdHash()).orElseGet(DeviceWideBan::new);
            wide.setInstallIdHash(d.getInstallIdHash());
            wide.setReason(reason.name());
            wide.setSupportRef(ref);
            wide.setBannedAt(now);
            wide.setBannedBy(who(actor));
            wide.setNote(DeviceSecurityEventLog.truncate(note, 1000));
            wide.setLiftedAt(null);
            wide.setLiftedBy(null);
            wideBans.save(wide);
            // Every account on this phone loses its open codes and tickets at once.
            for (CustomerDevice other : devices.findByInstallIdHash(d.getInstallIdHash())) {
                if (!other.getId().equals(d.getId())) cutOff(other, now);
            }
        }

        events.record(SecurityEventType.DEVICE_BANNED, actor.type(), actor.id(), d.getMsisdn(), d,
                b -> b.reason(reason.name()).supportRef(ref).decision(Decision.BANNED.name())
                        .ussdSessionId(actor.ussdSessionId()).note(DeviceSecurityEventLog.truncate(note, 1000))
                        .features(events.json(features)));
        metrics.enforcement(reason.deviceWide() ? "ban_device_wide" : "ban", reason.name(), true, actor.type().name());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("deviceWide", reason.deviceWide());
        meta.put("ussdUnlockable", ussdUnlockable);
        seal(AuditEventType.DEVICE_SECURITY_BANNED, actor, d, reason.name(), ref, note, meta);

        DeviceNotice.Type type = reason == BanReason.CUSTOMER_REPORTED ? DeviceNotice.Type.CUSTOMER_BLOCKED
                : ussdUnlockable ? DeviceNotice.Type.BANNED_USSD_UNLOCKABLE : DeviceNotice.Type.BANNED_SUPPORT_ONLY;
        publisher.publishEvent(new DeviceNotice(type, d.getMsisdn(), shortLabel(d), now, null, ref, null,
                actor.type() == ActorType.USSD));
        log.info("Device banned msisdn={} device={} reason={} deviceWide={} ussdUnlockable={} ref={}",
                DeviceIdentity.logMask(d.getMsisdn()), d.getPublicId(), reason, reason.deviceWide(), ussdUnlockable, ref);
        return d;
    }

    /** The active device-wide ban on this phone, if any. */
    public Optional<DeviceWideBan> activeWideBan(String installIdHash) {
        return wideBans.findById(installIdHash).filter(DeviceWideBan::active);
    }

    // ---- unlock --------------------------------------------------------------------

    /**
     * Lifts a block or ban. Never straight back to TRUSTED (§4): the device goes to
     * STEP_UP so the next sign-in still proves possession with an OTP, then the PIN.
     * Lifting a device-wide ban is part of the same act.
     */
    @Transactional
    public CustomerDevice unlock(CustomerDevice d, Actor actor, String note) {
        LocalDateTime now = now();
        String ref = SupportRefs.next();
        String liftedReason = d.getStateReason();
        d.transition(DeviceState.STEP_UP, OtpReason.UNLOCKED.name(), who(actor), now);
        d.clearStop();
        d.setSupportRef(ref);
        d.setLastUnlockedAt(now);
        d.setConsecutiveDeadChallenges(0);
        devices.save(d);
        activeWideBan(d.getInstallIdHash()).ifPresent(wide -> {
            wide.setLiftedAt(now);
            wide.setLiftedBy(who(actor));
            wideBans.save(wide);
        });
        events.record(SecurityEventType.DEVICE_UNLOCKED, actor.type(), actor.id(), d.getMsisdn(), d,
                b -> b.reason(liftedReason).supportRef(ref).ussdSessionId(actor.ussdSessionId())
                        .note(DeviceSecurityEventLog.truncate(note, 1000)));
        metrics.enforcement("unlock", liftedReason, true, actor.type().name());
        seal(AuditEventType.DEVICE_SECURITY_UNLOCKED, actor, d, liftedReason, ref, note, null);
        publisher.publishEvent(new DeviceNotice(
                actor.type() == ActorType.USSD ? DeviceNotice.Type.UNLOCKED_USSD : DeviceNotice.Type.UNLOCKED_BY_SUPPORT,
                d.getMsisdn(), shortLabel(d), now, null, ref, null, actor.type() == ActorType.USSD));
        log.info("Device unlocked msisdn={} device={} by={} lifted={} ref={}",
                DeviceIdentity.logMask(d.getMsisdn()), d.getPublicId(), actor.type(), liftedReason, ref);
        return d;
    }

    // ---- remove / forget -------------------------------------------------------------

    /** Removes the device (§5.6). Its next sign-in starts at NEW; a renewal cuts it off within 15 minutes. */
    @Transactional
    public CustomerDevice revoke(CustomerDevice d, Actor actor, String note) {
        LocalDateTime now = now();
        d.transition(DeviceState.REVOKED, null, who(actor), now);
        d.clearStop();
        d.setTrustedUntil(null);
        d.setPinGraceUntil(null);
        // A removed phone that comes back must prove the SIM again.
        d.setOtpVerifiedAt(null);
        devices.save(d);
        cutOff(d, now);
        events.record(SecurityEventType.DEVICE_REVOKED, actor.type(), actor.id(), d.getMsisdn(), d,
                b -> b.ussdSessionId(actor.ussdSessionId()).note(DeviceSecurityEventLog.truncate(note, 1000)));
        metrics.enforcement("revoke", null, true, actor.type().name());
        if (actor.type() != ActorType.CUSTOMER) {
            seal(AuditEventType.DEVICE_SECURITY_REVOKED, actor, d, null, null, note, null);
        }
        publisher.publishEvent(new DeviceNotice(DeviceNotice.Type.REVOKED, d.getMsisdn(), shortLabel(d), now,
                null, null, null, false));
        return d;
    }

    /**
     * Forgets the pairing entirely (§14.1's trust-reset tool): back to NEW, no
     * cooling, no trust — the next sign-in is a first sign-in. Silent: this is a
     * test and support tool, not something that happened to the customer.
     */
    @Transactional
    public CustomerDevice resetTrust(CustomerDevice d, Actor actor, String note) {
        LocalDateTime now = now();
        d.transition(DeviceState.NEW, null, who(actor), now);
        d.clearStop();
        d.setTrustedUntil(null);
        d.setBoundAt(null);
        d.setCoolingUntil(null);
        d.setOtpVerifiedAt(null);
        d.setPinGraceUntil(null);
        d.setConsecutiveDeadChallenges(0);
        devices.save(d);
        cutOff(d, now);
        events.record(SecurityEventType.DEVICE_TRUST_RESET, actor.type(), actor.id(), d.getMsisdn(), d,
                b -> b.note(DeviceSecurityEventLog.truncate(note, 1000)));
        seal(AuditEventType.DEVICE_SECURITY_TRUST_RESET, actor, d, null, null, note, null);
        return d;
    }

    // ---- trust -----------------------------------------------------------------------

    /**
     * Binds a PENDING_PIN device after the broker reports a successful login (§5.4):
     * TRUSTED for 90 days (30 after a fraud flag).
     *
     * <p>What else happens depends on whether the phone has PROVED it holds the SIM
     * (a verified sign-in OTP, {@link CustomerDevice#possessionVerified()}):
     * <ul>
     *   <li><b>Proved</b> — the pair's first bind starts the 24-hour cooling
     *       period (§8.6) and tells the customer a new phone signed in (§10).</li>
     *   <li><b>Not proved</b> — only watch mode answers TOKEN without a code, so
     *       this trust is PROVISIONAL: no cooling and no notice (the phone is almost
     *       always the customer's own, and every existing customer would otherwise
     *       be told "a new phone signed in" the day the broker switches to DTX), and
     *       {@code DeviceSignInService} asks it for one code once OTP is enforced.
     *       Without that, every phone that signed in while DTX was only watching
     *       — a stolen-PIN one included — would stay exempt for the whole trust
     *       window after enforcement began.</li>
     * </ul>
     *
     * @return true when this was the pair's first bind
     */
    @Transactional
    public boolean bind(CustomerDevice d, CustomerSecurityProfile profile) {
        LocalDateTime now = now();
        DeviceSecurityProperties.Trust trust = properties.getTrust();
        int days = profile != null && profile.fraudFlagged() ? trust.getFraudFlagDays() : trust.getDays();
        boolean first = d.getBoundAt() == null;
        boolean proved = d.possessionVerified();
        // A phone first bound provisionally and confirmed later is NOT announced when
        // the code arrives: it has been the customer's phone all along, and doing so
        // would message every watch-mode customer on the day OTP is switched on.
        boolean firstProved = first && proved;
        d.transition(DeviceState.TRUSTED, null, ActorType.BROKER.name(), now);
        d.setTrustedUntil(now.plusDays(days));
        d.setPinGraceUntil(null);
        d.setConsecutiveDeadChallenges(0);
        if (first) {
            d.setBoundAt(now);
        }
        if (firstProved) {
            d.setCoolingUntil(now.plus(trust.getCooling()));
        }
        devices.save(d);
        events.record(SecurityEventType.DEVICE_BOUND, ActorType.BROKER, null, d.getMsisdn(), d,
                b -> b.reason(!proved ? (first ? "FIRST_BIND_PROVISIONAL" : "REBIND_PROVISIONAL")
                        : first ? "FIRST_BIND" : "REBIND"));
        if (firstProved) {
            publisher.publishEvent(new DeviceNotice(DeviceNotice.Type.NEW_DEVICE_BOUND, d.getMsisdn(), shortLabel(d),
                    now, null, null, profile == null ? null : profile.getPreferredChannel(), false));
        }
        return first;
    }

    // ---- lazy expiry ------------------------------------------------------------------

    /**
     * Applies the transitions that happen "on their own": a temporary block whose
     * time is up, an integrity hold whose check now passes, a PIN grace that ran
     * out. Evaluated when the device is next looked at rather than by a sweeper, so
     * there is no window in which a sweeper and a sign-in race over the same row.
     * After any block lifts the device asks for an OTP (§8.4: "and an OTP when it
     * lifts") — conservative for every reason, not only RISK.
     */
    @Transactional
    public CustomerDevice refresh(CustomerDevice d, boolean integrityClean) {
        LocalDateTime now = now();
        if (d.getState() == DeviceState.TEMP_BLOCKED) {
            boolean hold = d.getBlockedUntil() == null;
            if ((!hold && !d.getBlockedUntil().isAfter(now)) || (hold && integrityClean)) {
                String lifted = d.getStateReason();
                d.transition(DeviceState.STEP_UP, OtpReason.RISK.name(), ActorType.SYSTEM.name(), now);
                d.clearStop();
                devices.save(d);
                events.record(hold ? SecurityEventType.INTEGRITY_HOLD_LIFTED : SecurityEventType.BLOCK_LIFTED,
                        ActorType.SYSTEM, null, d.getMsisdn(), d, b -> b.reason(lifted));
            }
        } else if (d.getState() == DeviceState.PENDING_PIN && !d.inPinGrace(now)) {
            DeviceState back = d.getPriorState() == null || d.getPriorState() == DeviceState.PENDING_PIN
                    ? DeviceState.NEW : d.getPriorState();
            String reason = back == DeviceState.STEP_UP ? OtpReason.RISK.name() : null;
            d.transition(back, reason, ActorType.SYSTEM.name(), now);
            d.setPinGraceUntil(null);
            devices.save(d);
        }
        return d;
    }

    // ---- helpers -----------------------------------------------------------------------

    /** Kills the device's open codes and unspent tickets, so an in-flight sign-in cannot complete. */
    private void cutOff(CustomerDevice d, LocalDateTime now) {
        challenges.voidOpenForDevice(d.getId(), now);
        tickets.voidOpenForDevice(d.getId(), now);
    }

    private void seal(AuditEventType type, Actor actor, CustomerDevice d, String reason, String ref,
                      String note, Map<String, ?> extra) {
        if (!actor.person()) return;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("msisdn", MsisdnMasking.mask(d.getMsisdn()));
        meta.put("channel", actor.type().name());
        if (reason != null) meta.put("reason", reason);
        if (ref != null) meta.put("supportRef", ref);
        if (actor.ussdSessionId() != null) meta.put("ussdSessionId", actor.ussdSessionId());
        if (note != null) meta.put("note", DeviceSecurityEventLog.truncate(note, 500));
        if (extra != null) meta.putAll(extra);
        auditService.recordSuccess(type,
                actor.type() == ActorType.SUPPORT ? actor.id() : "msisdn:" + MsisdnMasking.mask(d.getMsisdn()),
                actor.type() == ActorType.SUPPORT ? AuditService.ACTOR_TYPE_USER : AuditService.ACTOR_TYPE_ANONYMOUS,
                d.getPublicId().toString(), "DEVICE", meta,
                actor.auditContext() == null ? AuditContext.none() : actor.auditContext());
    }

    private static String who(Actor actor) {
        return actor.id() == null ? actor.type().name() : actor.type().name() + ":" + actor.id();
    }

    static String shortLabel(CustomerDevice d) {
        return DeviceIdentity.shortLabel(d.getManufacturer(), d.getModel(), d.getPlatform());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
