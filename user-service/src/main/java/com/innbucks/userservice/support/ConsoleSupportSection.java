package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.DeviceSecurityMessages;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.OrganizationProduct;
import com.innbucks.userservice.entity.ServiceRequest;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.ServiceRequestRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleAccountView;
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleSectionData;
import com.innbucks.userservice.support.dto.SupportDTOs.OrganizationView;
import com.innbucks.userservice.support.dto.SupportDTOs.SectionView;
import com.innbucks.userservice.support.dto.SupportDTOs.ServiceRequestView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Foundry console section (design §3.2): the account a merchant, organizer
 * or their colleague signs in to the console with — status, roles,
 * organizations, second factor, lockout, last sign-in and service requests —
 * with a sentence telling the agent what to do. In-process: everything is read
 * here, in user-service.
 *
 * <p>A "console account" is one that uses the console: it holds a role other than
 * CUSTOMER, belongs to an organization, or is a staff account. A plain super-app
 * customer is the InnBucks 2.0 section's business, not this one's.
 *
 * <p>The view is an allow-list ({@link ConsoleAccountView}): no password hash,
 * TOTP secret, backup codes, token version, OTP, or organization contact details
 * — and the business OWNERs an MFA reset notifies are never shown.
 */
@Component
public class ConsoleSupportSection {

    public static final String NAME = "console";

    public static final String ACTION_UNLOCK = "unlock";
    public static final String ACTION_SEND_PASSWORD_RESET = "send-password-reset";
    public static final String ACTION_MFA_RESET = "mfa/reset";

    private final OrganizationMemberRepository members;
    private final OrganizationRepository organizations;
    private final OrganizationProductRepository products;
    private final ServiceRequestRepository serviceRequests;
    private final SupportStaffTargets staffTargets;
    private final DeviceSecurityMessages messages;

    public ConsoleSupportSection(OrganizationMemberRepository members, OrganizationRepository organizations,
                                 OrganizationProductRepository products, ServiceRequestRepository serviceRequests,
                                 SupportStaffTargets staffTargets, DeviceSecurityMessages messages) {
        this.members = members;
        this.organizations = organizations;
        this.products = products;
        this.serviceRequests = serviceRequests;
        this.staffTargets = staffTargets;
        this.messages = messages;
    }

    /** True when the account uses the Foundry console (see the class javadoc). */
    public boolean isConsoleAccount(User user) {
        if (user == null) return false;
        if (user.getRoles() != null && user.getRoles().stream()
                .anyMatch(r -> !User.Role.CUSTOMER.name().equals(r))) {
            return true;
        }
        return members.existsByUserId(user.getId()) || staffTargets.isStaffAccount(user);
    }

    /** The section for these accounts (already filtered to console accounts). */
    public SectionView<ConsoleSectionData> section(List<User> accounts, String matchedBy, SupportAgent agent,
                                                   LocalDateTime now) {
        if (accounts.isEmpty()) {
            return new SectionView<>("NOT_FOUND", null,
                    "No Foundry console account uses this " + ("email".equals(matchedBy) ? "email address." : "number."),
                    "If the caller says they use the console, ask which email they sign in with and search by that.",
                    new ConsoleSectionData(List.of()));
        }
        List<ConsoleAccountView> views = accounts.stream()
                .sorted(Comparator.comparing(User::getId))
                .map(u -> view(u, agent, now))
                .toList();
        String summary = views.size() == 1
                ? "1 Foundry console account: " + describe(views.get(0)) + "."
                : views.size() + " Foundry console accounts: "
                        + views.stream().map(ConsoleSupportSection::describe).collect(Collectors.joining("; ")) + ".";
        String guidance = views.size() == 1 ? views.get(0).agentGuidance()
                : "More than one account matches. Confirm with the caller which one they sign in to before acting.";
        return new SectionView<>("OK", matchedBy, summary, guidance, new ConsoleSectionData(views));
    }

    public ConsoleAccountView view(User u, SupportAgent agent, LocalDateTime now) {
        boolean staff = staffTargets.isStaffAccount(u);
        LocalDateTime lockedUntil = future(u.getLockedUntil(), now);
        LocalDateTime mfaLockedUntil = future(u.getMfaLockedUntil(), now);
        boolean mfaEnrolled = u.isMfaEnabled() && u.getMfaSecret() != null;
        String status = !u.isApproved() ? "PENDING_APPROVAL" : (u.isActive() ? "ACTIVE" : "DEACTIVATED");

        List<OrganizationView> orgs = organizationsOf(u);
        List<ServiceRequestView> requests = serviceRequests.findByUserIdOrderByCreatedAtDesc(u.getId()).stream()
                .map(r -> request(r, now)).toList();

        List<String> actions = new ArrayList<>();
        if (!staff) {
            boolean locked = lockedUntil != null || mfaLockedUntil != null || u.getFailedLoginAttempts() > 0
                    || u.getMfaFailedAttempts() > 0;
            if (locked && agent.holds(PermissionCatalog.SUPPORT_CONSOLE_MANAGE)) actions.add(ACTION_UNLOCK);
            if (u.isActive() && u.getEmail() != null && agent.holds(PermissionCatalog.SUPPORT_CONSOLE_MANAGE)) {
                actions.add(ACTION_SEND_PASSWORD_RESET);
            }
            if (mfaEnrolled && agent.holds(PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET)) actions.add(ACTION_MFA_RESET);
        }
        return new ConsoleAccountView(u.getId(), u.getUserUuid(), name(u), u.getEmail(), u.getPhoneNumber(), status,
                List.copyOf(new TreeSet<>(u.getRoles())), staff, mfaEnrolled, lockedUntil, mfaLockedUntil,
                u.getFailedLoginAttempts(), u.getLastSignInAt(), u.isMustChangePassword(), u.getCreatedAt(), orgs,
                requests, guidance(u, staff, status, lockedUntil, mfaLockedUntil, mfaEnrolled, now), List.copyOf(actions));
    }

    /** What the agent should say or do, in one or two sentences — never a state to interpret. */
    String guidance(User u, boolean staff, String status, LocalDateTime lockedUntil, LocalDateTime mfaLockedUntil,
                    boolean mfaEnrolled, LocalDateTime now) {
        if (staff) {
            return "This is an InnBucks staff account; ask a SUPER_ADMIN. Support can't change it.";
        }
        if ("PENDING_APPROVAL".equals(status)) {
            return "This registration is waiting for approval by an InnBucks administrator. Support can't approve it.";
        }
        if ("DEACTIVATED".equals(status)) {
            return "This account is deactivated and can't sign in. Support can't reactivate it; an administrator "
                    + "decides that.";
        }
        List<String> parts = new ArrayList<>();
        if (lockedUntil != null) {
            parts.add("Locked after too many wrong passwords until " + messages.time(lockedUntil, now, false)
                    + ". After verifying the caller you can unlock it now.");
        }
        if (mfaLockedUntil != null) {
            parts.add("The two-factor step is locked after too many wrong codes until "
                    + messages.time(mfaLockedUntil, now, false) + ". After verifying the caller you can unlock it now.");
        }
        if (u.isMustChangePassword()) {
            parts.add("They must replace a temporary password at their next sign-in.");
        }
        if (!mfaEnrolled) {
            parts.add("Two-factor sign-in isn't set up yet; they'll be asked to set it up when they next sign in.");
        }
        if (parts.isEmpty()) {
            return "Signs in normally. If they've forgotten the password, send a reset code.";
        }
        return String.join(" ", parts);
    }

    private ServiceRequestView request(ServiceRequest r, LocalDateTime now) {
        String when = messages.time(r.getCreatedAt(), now, false);
        String guidance = switch (r.getStatus()) {
            case PENDING -> "Waiting for an InnBucks administrator to review it (submitted " + when + "). Support "
                    + "can't approve requests; if it's urgent, escalate to the product team.";
            case APPROVED -> "Approved" + (r.getReviewedAt() == null ? "" : " at " + messages.time(r.getReviewedAt(), now, false))
                    + ". If the new menu isn't showing, ask them to sign out and sign in again.";
            case REJECTED -> "Rejected" + (r.getReviewedAt() == null ? "" : " at " + messages.time(r.getReviewedAt(), now, false))
                    + ". They can send a new request that answers the reason given.";
        };
        return new ServiceRequestView(r.getId(), r.getService(), r.getStatus().name(), r.getCreatedAt(),
                r.getReviewedAt(), r.getDecisionReason(), guidance);
    }

    private List<OrganizationView> organizationsOf(User u) {
        List<OrganizationMember> mine = members.findByUserId(u.getId());
        if (mine.isEmpty()) return List.of();
        List<UUID> ids = mine.stream().map(OrganizationMember::getOrganizationId).toList();
        Map<UUID, Organization> byId = organizations.findAllById(ids).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
        Map<UUID, List<String>> productsById = products.findByOrganizationIdIn(ids).stream()
                .filter(p -> p.getStatus() == OrganizationProduct.Status.ACTIVE)
                .collect(Collectors.groupingBy(OrganizationProduct::getOrganizationId,
                        Collectors.mapping(OrganizationProduct::getProduct, Collectors.toList())));
        List<OrganizationView> out = new ArrayList<>();
        for (OrganizationMember m : mine) {
            Organization o = byId.get(m.getOrganizationId());
            if (o == null) continue;
            out.add(new OrganizationView(o.getId(), o.getName(), m.getRole().name(), o.getStatus().name(),
                    List.copyOf(new TreeSet<>(productsById.getOrDefault(o.getId(), List.of())))));
        }
        out.sort(Comparator.comparing(OrganizationView::name, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    private static String describe(ConsoleAccountView v) {
        return v.name() + " (" + String.join(", ", v.roles()) + (v.staffAccount() ? ", staff" : "")
                + ("ACTIVE".equals(v.status()) ? "" : ", " + v.status().toLowerCase(java.util.Locale.ROOT).replace('_', ' '))
                + ")";
    }

    static String name(User u) {
        String first = u.getFirstName() == null ? "" : u.getFirstName().strip();
        String last = u.getLastName() == null ? "" : u.getLastName().strip();
        String full = (first + " " + last).strip();
        return full.isEmpty() ? null : full;
    }

    private static LocalDateTime future(Instant at, LocalDateTime now) {
        if (at == null) return null;
        LocalDateTime utc = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        return utc.isAfter(now) ? utc : null;
    }
}
