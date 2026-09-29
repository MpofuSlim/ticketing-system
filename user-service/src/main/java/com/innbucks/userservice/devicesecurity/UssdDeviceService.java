package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.devicesecurity.dto.UssdDTOs.UssdDevice;
import com.innbucks.userservice.devicesecurity.dto.UssdDTOs.UssdDeviceActionRequest;
import com.innbucks.userservice.devicesecurity.dto.UssdDTOs.UssdDeviceActionResponse;
import com.innbucks.userservice.devicesecurity.dto.UssdDTOs.UssdDevicesRequest;
import com.innbucks.userservice.devicesecurity.dto.UssdDTOs.UssdDevicesResponse;
import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import com.innbucks.userservice.devicesecurity.repository.CustomerDeviceRepository;
import com.innbucks.userservice.devicesecurity.repository.DeviceSecurityEventRepository;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.util.MsisdnMasking;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The *569# security menu (contract §9): list and unlock the dialling number's
 * blocked phones, and — for a lost or stolen phone — list and block its active
 * ones. Every unlock and block confirms to the customer by SMS first: the USSD
 * session is tied to the SIM that dialled, so an SMS to that SIM is the
 * strongest confirmation there is (WhatsApp is the fallback).
 *
 * <p><b>Trust model.</b> The USSD service authenticates with its own
 * {@code x-api-key}, sends the DIALLING number as the mobile network reports it,
 * and has already checked the customer's PIN with staging (§9.1 step 3). DTX
 * never sees the PIN. It only ever acts on that number's own phones.
 *
 * <p><b>Safeguards (§9.3):</b> at most 3 unlock attempts per number per day
 * (refusals count); at most one successful unlock per phone per 7 days, and a
 * phone banned again inside that window is support-only; SHARED_DEVICE,
 * CONFIRMED_FRAUD and SIM-swap bans are never USSD-unlockable; a fraud-desk
 * suspicion only after 24 hours; an integrity hold never (it lifts when the
 * phone's security check passes). Every attempt — refused or not — is on the
 * decision log with the USSD session id, and on the audit chain.
 */
@Service
@Slf4j
public class UssdDeviceService {

    public static final String RESULT_UNLOCKED = "UNLOCKED";
    public static final String RESULT_NOT_ELIGIBLE = "NOT_ELIGIBLE";
    public static final String RESULT_TRY_LATER = "TRY_LATER";
    public static final String RESULT_NOT_FOUND = "NOT_FOUND";
    public static final String RESULT_BLOCKED = "BLOCKED";
    public static final String RESULT_ALREADY_BLOCKED = "ALREADY_BLOCKED";

    private final CustomerDeviceRepository devices;
    private final DeviceSecurityEventRepository eventRepository;
    private final DeviceEnforcement enforcement;
    private final DeviceSecurityEventLog events;
    private final DeviceSecurityMessages messages;
    private final AuditService auditService;
    private final DeviceSecurityProperties properties;
    private final Clock clock;
    private final String deploymentCountry;

    public UssdDeviceService(CustomerDeviceRepository devices, DeviceSecurityEventRepository eventRepository,
                             DeviceEnforcement enforcement, DeviceSecurityEventLog events,
                             DeviceSecurityMessages messages, AuditService auditService,
                             DeviceSecurityProperties properties, Clock deviceSecurityClock,
                             @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.devices = devices;
        this.eventRepository = eventRepository;
        this.enforcement = enforcement;
        this.events = events;
        this.messages = messages;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = deviceSecurityClock;
        this.deploymentCountry = deploymentCountry;
    }

    // ---- Unlock device ---------------------------------------------------------------

    /** The dialling number's blocked phones, each marked with whether *569# can lift it. */
    @Transactional
    public UssdDevicesResponse blocked(UssdDevicesRequest req) {
        String msisdn = normalise(req.msisdn());
        LocalDateTime now = now();
        List<UssdDevice> out = new ArrayList<>();
        StringBuilder menu = new StringBuilder(messages.ussdChooseToUnlock());
        int option = 0;
        for (CustomerDevice d : devices.findByMsisdnOrderByLastSeenAtDesc(msisdn)) {
            enforcement.refresh(d, false);
            boolean wide = enforcement.activeWideBan(d.getInstallIdHash()).isPresent();
            if (!d.getState().isStopped() && !wide) continue;
            String label = DeviceEnforcement.shortLabel(d);
            String menuLabel = messages.ussdMenuLabel(label, d.getBlockedAt(), true);
            option++;
            out.add(new UssdDevice(option, d.getPublicId(), label, wide ? DeviceState.BANNED.name() : d.getState().name(),
                    d.getBlockedAt(), d.getBlockedUntil(), !wide && refusal(d, now) == null, menuLabel));
            menu.append('\n').append(option).append(". ").append(menuLabel);
        }
        return new UssdDevicesResponse(out, out.isEmpty() ? messages.ussdNoBlockedPhones() : menu.toString());
    }

    @Transactional
    public UssdDeviceActionResponse unlock(UssdDeviceActionRequest req, AuditContext ctx) {
        String msisdn = normalise(req.msisdn());
        LocalDateTime now = now();
        DeviceSecurityProperties.Ussd ussd = properties.getUssd();

        long attempts = eventRepository.countByMsisdnAndActorTypeAndEventTypeInAndOccurredAtAfter(msisdn,
                ActorType.USSD.name(), List.of(SecurityEventType.DEVICE_UNLOCKED.name(),
                        SecurityEventType.USSD_UNLOCK_REFUSED.name()), now.minusDays(1));
        if (attempts >= ussd.getMaxUnlockAttemptsPerDay()) {
            refuse(SecurityEventType.USSD_UNLOCK_REFUSED, msisdn, null, "ATTEMPTS_PER_DAY", req, ctx);
            return new UssdDeviceActionResponse(RESULT_TRY_LATER, null, null, messages.ussdTryLater(null, now));
        }

        CustomerDevice d = devices.findByPublicIdAndMsisdn(req.deviceId(), msisdn).orElse(null);
        if (d == null) {
            refuse(SecurityEventType.USSD_UNLOCK_REFUSED, msisdn, null, "NOT_FOUND", req, ctx);
            return new UssdDeviceActionResponse(RESULT_NOT_FOUND, null, null, messages.ussdNotFound());
        }
        enforcement.refresh(d, false);
        String label = DeviceEnforcement.shortLabel(d);

        if (enforcement.activeWideBan(d.getInstallIdHash()).isPresent()) {
            String ref = enforcement.activeWideBan(d.getInstallIdHash()).get().getSupportRef();
            refuse(SecurityEventType.USSD_UNLOCK_REFUSED, msisdn, d, "DEVICE_WIDE_BAN", req, ctx);
            return new UssdDeviceActionResponse(RESULT_NOT_ELIGIBLE, label, ref, messages.ussdSupportOnly(ref));
        }
        if (!d.getState().isStopped()) {
            // Already free (a temporary block ran out while the customer dialled).
            refuse(SecurityEventType.USSD_UNLOCK_REFUSED, msisdn, d, "NOT_BLOCKED", req, ctx);
            return new UssdDeviceActionResponse(RESULT_UNLOCKED, label, null, messages.ussdUnlocked());
        }
        String refusal = refusal(d, now);
        if (refusal != null) {
            refuse(SecurityEventType.USSD_UNLOCK_REFUSED, msisdn, d, refusal, req, ctx);
            return switch (refusal) {
                case "TOO_EARLY" -> new UssdDeviceActionResponse(RESULT_TRY_LATER, label, d.getSupportRef(),
                        messages.ussdTryLater(d.getUnlockableAfter(), now));
                case "INTEGRITY_HOLD" -> new UssdDeviceActionResponse(RESULT_NOT_ELIGIBLE, label, d.getSupportRef(),
                        messages.ussdIntegrityHold(d.getSupportRef()));
                default -> new UssdDeviceActionResponse(RESULT_NOT_ELIGIBLE, label, d.getSupportRef(),
                        messages.ussdSupportOnly(d.getSupportRef()));
            };
        }
        enforcement.unlock(d, new DeviceEnforcement.Actor(ActorType.USSD, "ussd", req.ussdSessionId(), ctx),
                "Unlocked by the customer on *569#");
        log.info("Device unlocked on *569# msisdn={} device={} session={}", DeviceIdentity.logMask(msisdn),
                d.getPublicId(), req.ussdSessionId());
        return new UssdDeviceActionResponse(RESULT_UNLOCKED, label, d.getSupportRef(), messages.ussdUnlocked());
    }

    /** Why *569# may not lift this stop, or null when it may. */
    String refusal(CustomerDevice d, LocalDateTime now) {
        if (d.getLastUnlockedAt() != null
                && d.getLastUnlockedAt().isAfter(now.minus(properties.getUssd().getUnlockCooldown()))) {
            return "UNLOCKED_RECENTLY";
        }
        if (d.getState() == DeviceState.TEMP_BLOCKED) {
            return BlockReason.INTEGRITY_HOLD.name().equals(d.getStateReason()) ? "INTEGRITY_HOLD" : null;
        }
        if (d.getState() == DeviceState.BANNED) {
            BanReason reason = banReason(d.getStateReason());
            if (reason == null || !reason.ussdUnlockable() || !d.isUssdUnlockable()) return "SUPPORT_ONLY";
            if (d.getUnlockableAfter() != null && d.getUnlockableAfter().isAfter(now)) return "TOO_EARLY";
            return null;
        }
        return null;
    }

    // ---- Block device (lost or stolen phone) ----------------------------------------

    /** The dialling number's phones that can still be blocked. */
    @Transactional
    public UssdDevicesResponse active(UssdDevicesRequest req) {
        String msisdn = normalise(req.msisdn());
        List<UssdDevice> out = new ArrayList<>();
        StringBuilder menu = new StringBuilder(messages.ussdChooseToBlock());
        int option = 0;
        for (CustomerDevice d : devices.findByMsisdnOrderByLastSeenAtDesc(msisdn)) {
            if (d.getState() == DeviceState.BANNED) continue;
            String label = DeviceEnforcement.shortLabel(d);
            String menuLabel = messages.ussdActiveMenuLabel(label, d.getLastSeenAt());
            option++;
            out.add(new UssdDevice(option, d.getPublicId(), label, d.getState().name(), d.getBlockedAt(),
                    d.getBlockedUntil(), true, menuLabel));
            menu.append('\n').append(option).append(". ").append(menuLabel);
        }
        return new UssdDevicesResponse(out, out.isEmpty() ? messages.ussdNoActivePhones() : menu.toString());
    }

    /**
     * Blocks one of the number's phones until the customer unlocks it here — a
     * CUSTOMER_REPORTED ban. Its open codes and unspent tickets die at once, and a
     * silent renewal cuts it off within 15 minutes.
     */
    @Transactional
    public UssdDeviceActionResponse block(UssdDeviceActionRequest req, AuditContext ctx) {
        String msisdn = normalise(req.msisdn());
        LocalDateTime now = now();
        long blocksToday = eventRepository.countByMsisdnAndActorTypeAndEventTypeInAndOccurredAtAfter(msisdn,
                ActorType.USSD.name(), List.of(SecurityEventType.DEVICE_BANNED.name()), now.minusDays(1));
        if (blocksToday >= properties.getUssd().getMaxBlocksPerDay()) {
            refuse(SecurityEventType.USSD_BLOCK_REFUSED, msisdn, null, "BLOCKS_PER_DAY", req, ctx);
            return new UssdDeviceActionResponse(RESULT_TRY_LATER, null, null, messages.ussdBlockLimit());
        }
        CustomerDevice d = devices.findByPublicIdAndMsisdn(req.deviceId(), msisdn).orElse(null);
        if (d == null) {
            refuse(SecurityEventType.USSD_BLOCK_REFUSED, msisdn, null, "NOT_FOUND", req, ctx);
            return new UssdDeviceActionResponse(RESULT_NOT_FOUND, null, null, messages.ussdNotFound());
        }
        String label = DeviceEnforcement.shortLabel(d);
        if (d.getState() == DeviceState.BANNED) {
            return new UssdDeviceActionResponse(RESULT_ALREADY_BLOCKED, label, d.getSupportRef(),
                    messages.ussdAlreadyBlocked(label));
        }
        enforcement.ban(d, BanReason.CUSTOMER_REPORTED,
                new DeviceEnforcement.Actor(ActorType.USSD, "ussd", req.ussdSessionId(), ctx),
                "Blocked by the customer on *569#", null);
        log.info("Device blocked on *569# msisdn={} device={} session={}", DeviceIdentity.logMask(msisdn),
                d.getPublicId(), req.ussdSessionId());
        return new UssdDeviceActionResponse(RESULT_BLOCKED, label, d.getSupportRef(),
                messages.ussdBlocked(label, d.getSupportRef()));
    }

    // ---- helpers ---------------------------------------------------------------------

    private void refuse(SecurityEventType type, String msisdn, CustomerDevice d, String reason,
                        UssdDeviceActionRequest req, AuditContext ctx) {
        events.record(type, ActorType.USSD, "ussd", msisdn, d,
                b -> b.reason(reason).ussdSessionId(req.ussdSessionId()));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("msisdn", MsisdnMasking.mask(msisdn));
        meta.put("channel", ActorType.USSD.name());
        meta.put("ussdSessionId", req.ussdSessionId());
        auditService.recordFailure(type == SecurityEventType.USSD_BLOCK_REFUSED
                        ? AuditEventType.DEVICE_SECURITY_BANNED : AuditEventType.DEVICE_SECURITY_UNLOCKED,
                "msisdn:" + MsisdnMasking.mask(msisdn), AuditService.ACTOR_TYPE_ANONYMOUS,
                d == null ? String.valueOf(req.deviceId()) : d.getPublicId().toString(), "DEVICE",
                reason, meta, ctx == null ? AuditContext.none() : ctx);
        log.info("USSD {} refused msisdn={} reason={} session={}", type == SecurityEventType.USSD_BLOCK_REFUSED
                ? "block" : "unlock", DeviceIdentity.logMask(msisdn), reason, req.ussdSessionId());
    }

    private String normalise(String raw) {
        return MsisdnValidator.normalizeToE164(raw, deploymentCountry)
                .orElseThrow(() -> DeviceSecurityException.badRequest("msisdn", "msisdn is not a valid mobile number."));
    }

    static BanReason banReason(String stored) {
        if (stored == null) return null;
        try {
            return BanReason.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
