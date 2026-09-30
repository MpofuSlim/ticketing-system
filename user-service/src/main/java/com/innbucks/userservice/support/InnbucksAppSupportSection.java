package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.DeviceState;
import com.innbucks.userservice.devicesecurity.DeviceSupportService;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.CustomerOverview;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.EventView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.SupportDeviceView;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.support.dto.SupportDTOs.CustomerProfileView;
import com.innbucks.userservice.support.dto.SupportDTOs.DeviceSecurityView;
import com.innbucks.userservice.support.dto.SupportDTOs.InnbucksAppPhoneView;
import com.innbucks.userservice.support.dto.SupportDTOs.InnbucksAppSectionData;
import com.innbucks.userservice.support.dto.SupportDTOs.SectionView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The InnBucks 2.0 app section (design §3.2): the DTX device overview, read
 * in-process through {@link DeviceSupportService}, plus the customer's
 * registration facts from {@code customer_profiles}. Gated on
 * {@code device-security:read} — the same permission the call-center device
 * tools use.
 *
 * <p>Masked on the way out: every device's {@code lastIp} and every event's
 * address ({@link SupportMasking#ip}); event {@code features} are dropped (they
 * are the risk engine's inputs, not something an agent acts on). Install ids
 * never reach this view at all — {@link SupportDeviceView} has no field for one.
 *
 * <p>Writes stay on {@code /admin/device-security/**}: their contract shipped
 * with the console and is keyed by the msisdn and an opaque device id.
 */
@Component
public class InnbucksAppSupportSection {

    public static final String NAME = "innbucksApp";

    private final DeviceSupportService deviceSupport;
    private final CustomerProfileRepository customerProfiles;

    public InnbucksAppSupportSection(DeviceSupportService deviceSupport, CustomerProfileRepository customerProfiles) {
        this.deviceSupport = deviceSupport;
        this.customerProfiles = customerProfiles;
    }

    /**
     * @param phones      E.164 numbers, each with the account using it (null when no account does)
     * @param matchedBy   phone, email or reference
     */
    public SectionView<InnbucksAppSectionData> section(Map<String, Optional<User>> phones, String matchedBy) {
        if (phones.isEmpty()) {
            return new SectionView<>("NOT_FOUND", null,
                    "No phone number is on file for this email, so there is no InnBucks app record to show.",
                    "Ask the caller for the phone number they use with the InnBucks app and search by that.",
                    new InnbucksAppSectionData(List.of()));
        }
        List<InnbucksAppPhoneView> views = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        String guidance = null;
        for (Map.Entry<String, Optional<User>> e : phones.entrySet()) {
            CustomerOverview overview = deviceSupport.overview(e.getKey());
            CustomerProfileView profile = e.getValue()
                    .flatMap(u -> customerProfiles.findByUserId(u.getId()))
                    .map(InnbucksAppSupportSection::profile)
                    .orElse(null);
            DeviceSecurityView security = mask(overview);
            views.add(new InnbucksAppPhoneView(overview.msisdn(), profile, security));
            summaries.add((phones.size() > 1 ? SupportMasking.phone(overview.msisdn()) + ": " : "") + overview.summary());
            if (guidance == null) guidance = firstStoppedGuidance(security.devices());
        }
        boolean anything = views.stream().anyMatch(v -> v.customerProfile() != null
                || !v.deviceSecurity().devices().isEmpty() || !v.deviceSecurity().recentEvents().isEmpty());
        if (!anything) {
            return new SectionView<>("NOT_FOUND", matchedBy,
                    "This number has no InnBucks app account and no phone has tried to sign in with it.",
                    "If the caller says they use the app, check the number with them.",
                    new InnbucksAppSectionData(views));
        }
        return new SectionView<>("OK", matchedBy, String.join(" ", summaries),
                guidance == null ? "Nothing is stopping this customer's phones. If the app still refuses them, "
                        + "check the recent events." : guidance,
                new InnbucksAppSectionData(views));
    }

    static CustomerProfileView profile(CustomerProfile p) {
        return new CustomerProfileView(p.getRegistrationTier(), p.isVerified(), p.isPhoneVerified(),
                p.getPhoneVerifiedAt());
    }

    /** The overview with every address masked and the risk features dropped. */
    static DeviceSecurityView mask(CustomerOverview o) {
        List<SupportDeviceView> devices = o.devices().stream().map(InnbucksAppSupportSection::mask).toList();
        List<EventView> events = o.recentEvents().stream().map(InnbucksAppSupportSection::mask).toList();
        return new DeviceSecurityView(o.profile(), devices, o.counters(), events, o.summary());
    }

    static SupportDeviceView mask(SupportDeviceView d) {
        return new SupportDeviceView(d.deviceId(), d.msisdn(), d.label(), d.platform(), d.osVersion(), d.model(),
                d.manufacturer(), d.appVersion(), d.state(), d.stateReason(), d.blockedAt(), d.blockedUntil(),
                d.supportRef(), d.ussdUnlockable(), d.unlockableAfter(), d.trustedUntil(), d.boundAt(),
                d.coolingUntil(), d.otpVerifiedAt(), d.firstSeenAt(), d.lastSeenAt(), d.lastSeenNear(),
                SupportMasking.ip(d.lastIp()), d.lastUnlockedAt(), d.deviceWideBan(), d.otherAccountsOnDevice(),
                d.agentGuidance());
    }

    static EventView mask(EventView e) {
        return new EventView(e.id(), e.occurredAt(), e.type(), e.description(), e.decision(), e.evaluatedDecision(),
                e.reason(), e.supportRef(), e.actorType(), e.actorId(), e.channel(), e.purpose(), e.context(),
                e.deviceId(), e.ussdSessionId(), SupportMasking.ip(e.ipAddress()), e.riskScore(), null, e.note());
    }

    private static String firstStoppedGuidance(List<SupportDeviceView> devices) {
        for (SupportDeviceView d : devices) {
            if (d.deviceWideBan() != null || DeviceState.BANNED.name().equals(d.state())
                    || DeviceState.TEMP_BLOCKED.name().equals(d.state())) {
                return d.agentGuidance();
            }
        }
        return null;
    }
}
