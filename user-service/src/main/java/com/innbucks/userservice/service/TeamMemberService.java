package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.dto.CreateTeamMemberDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.TeamMemberEventAssignment;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.TeamMemberEventAssignmentRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.AuthenticatedCaller;
import com.innbucks.userservice.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Onboards EVENT_ORGANIZER team members (gate-staff / scanner operators).
 *
 * <p>Team-member identity is owned here (mirrors {@link ShopStaffService}),
 * the relation is one-to-one between team member and parent organizer via
 * {@link User#getCreatedByOrganizerUuid()}, stamped at creation from the
 * caller's {@code organizerUuid} JWT claim. A team member's JWT carries
 * the same {@code organizerUuid} so booking-service can authorize ticket
 * scans without a cross-service lookup.
 *
 * <p>Disable semantics: soft-delete only — the row stays for audit. We
 * flip {@link User#isActive()} to false (locks them out of login) and bump
 * {@link User#getTokenVersion()} (invalidates every still-live access
 * token instantly); refresh-token families are revoked in the same write
 * so the disabled member can't refresh into a fresh session.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamMemberService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TeamMemberEventAssignmentRepository assignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailNotificationClient emailNotificationClient;
    private final SmsNotificationClient smsNotificationClient;
    private final WhatsAppNotificationClient whatsAppNotificationClient;

    /** Deployment country pin. Team members are anchored to this cell — see
     *  {@link ShopStaffService#deploymentCountry} for the reasoning. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry;

    @Transactional
    public UserResponseDTO createTeamMember(CreateTeamMemberDTO req) {
        User caller = requireOrganizerCaller();
        UUID organizerUuid = caller.getUserUuid();

        if (userRepository.existsByEmail(req.getEmail())) {
            throw badRequest("Email already registered");
        }
        if (userRepository.existsByPhoneNumberAndHomeCountry(req.getPhoneNumber(), deploymentCountry)) {
            throw badRequest("Phone number already registered");
        }

        String tempPassword = TemporaryPasswordGenerator.generate();
        User member = User.builder()
                .firstName(req.getFirstName())
                .middleName(req.getMiddleName())
                .lastName(req.getLastName())
                .email(req.getEmail())
                .phoneNumber(req.getPhoneNumber())
                .homeCountry(deploymentCountry)
                .password(passwordEncoder.encode(tempPassword))
                .roles(EnumSet.of(User.Role.TEAM_MEMBER))
                // Team members operate the ticketing scanner; grant the
                // ticketing bundle so their JWT carries the same services
                // claim an organizer would.
                .defaultServices(new LinkedHashSet<>(List.of(Services.TICKETING)))
                .active(true)
                .createdByOrganizerUuid(organizerUuid)
                .build();
        userRepository.save(member);
        log.info("Created TEAM_MEMBER userId={} userUuid={} organizerUuid={} by={}",
                member.getId(), member.getUserUuid(), organizerUuid, caller.getEmail());
        notifyOnboarding(member, tempPassword);
        return UserResponseDTO.from(member);
    }

    @Transactional(readOnly = true)
    public List<UserResponseDTO> listMyTeam() {
        User caller = requireOrganizerCaller();
        return userRepository.findByCreatedByOrganizerUuid(caller.getUserUuid()).stream()
                .map(UserResponseDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponseDTO getMyTeamMember(UUID teamMemberUuid) {
        User caller = requireOrganizerCaller();
        User member = loadMemberOwnedByCaller(teamMemberUuid, caller.getUserUuid());
        return UserResponseDTO.from(member);
    }

    /**
     * Soft-delete. We never DELETE the row — booking_items.redeemed_by_user_uuid
     * + redeemed_by_name keep the audit trail readable for tickets this
     * member scanned, and a hard delete would orphan that history. The
     * organizer can re-enable later via {@link #enableTeamMember(UUID)}.
     */
    @Transactional
    public UserResponseDTO disableTeamMember(UUID teamMemberUuid) {
        User caller = requireOrganizerCaller();
        User member = loadMemberOwnedByCaller(teamMemberUuid, caller.getUserUuid());
        if (!member.isActive()) {
            // Already disabled — return current state, nothing to do.
            return UserResponseDTO.from(member);
        }
        member.setActive(false);
        // Bump the session epoch so every outstanding access token the
        // member holds is rejected by JwtFilter on its next call —
        // immediate revoke instead of waiting out the natural TTL.
        member.setTokenVersion(member.getTokenVersion() + 1);
        userRepository.save(member);
        // Revoke every still-live refresh token so they can't /auth/refresh
        // back into a fresh access token.
        int revokedFamilies = refreshTokenRepository.revokeAllForUser(member.getId(), Instant.now());
        log.info("Disabled TEAM_MEMBER userUuid={} organizerUuid={} by={} revokedRefreshFamilies={}",
                member.getUserUuid(), caller.getUserUuid(), caller.getEmail(), revokedFamilies);
        return UserResponseDTO.from(member);
    }

    @Transactional
    public UserResponseDTO enableTeamMember(UUID teamMemberUuid) {
        User caller = requireOrganizerCaller();
        User member = loadMemberOwnedByCaller(teamMemberUuid, caller.getUserUuid());
        if (member.isActive()) {
            return UserResponseDTO.from(member);
        }
        member.setActive(true);
        userRepository.save(member);
        log.info("Re-enabled TEAM_MEMBER userUuid={} organizerUuid={} by={}",
                member.getUserUuid(), caller.getUserUuid(), caller.getEmail());
        return UserResponseDTO.from(member);
    }

    // ===================== event assignments =====================

    /**
     * Assigns the team member to an event (idempotent). The first assignment
     * for a member flips them from organizer-wide scanning to "assigned events
     * only" — see {@link #canScanEvent}. Returns the member's full current
     * assignment set so the caller can refresh its view in one round trip.
     */
    @Transactional
    public List<UUID> assignEvent(UUID teamMemberUuid, UUID eventId) {
        User caller = requireOrganizerCaller();
        loadMemberOwnedByCaller(teamMemberUuid, caller.getUserUuid());
        if (!assignmentRepository.existsByTeamMemberUserUuidAndEventId(teamMemberUuid, eventId)) {
            assignmentRepository.save(TeamMemberEventAssignment.builder()
                    .teamMemberUserUuid(teamMemberUuid)
                    .eventId(eventId)
                    .assignedByOrganizerUuid(caller.getUserUuid())
                    .build());
            log.info("Assigned TEAM_MEMBER userUuid={} to eventId={} by organizerUuid={}",
                    teamMemberUuid, eventId, caller.getUserUuid());
        }
        return assignedEventIds(teamMemberUuid);
    }

    /**
     * Removes an event assignment (idempotent). If this was the member's last
     * assignment they revert to organizer-wide scanning (no rows = wide open).
     */
    @Transactional
    public List<UUID> unassignEvent(UUID teamMemberUuid, UUID eventId) {
        User caller = requireOrganizerCaller();
        loadMemberOwnedByCaller(teamMemberUuid, caller.getUserUuid());
        long removed = assignmentRepository.deleteByTeamMemberUserUuidAndEventId(teamMemberUuid, eventId);
        if (removed > 0) {
            log.info("Unassigned TEAM_MEMBER userUuid={} from eventId={} by organizerUuid={}",
                    teamMemberUuid, eventId, caller.getUserUuid());
        }
        return assignedEventIds(teamMemberUuid);
    }

    @Transactional(readOnly = true)
    public List<UUID> listAssignedEvents(UUID teamMemberUuid) {
        User caller = requireOrganizerCaller();
        loadMemberOwnedByCaller(teamMemberUuid, caller.getUserUuid());
        return assignedEventIds(teamMemberUuid);
    }

    /**
     * Scan-time authorization data for booking-service (called S2S, NOT through
     * an organizer session — so no ownership check here; the caller is trusted
     * via X-Internal-Token). Implements the product rule: a member with no
     * assignment rows is organizer-wide (allowed); a member with rows may scan
     * only the events they're assigned to.
     */
    @Transactional(readOnly = true)
    public boolean canScanEvent(UUID teamMemberUuid, UUID eventId) {
        if (!assignmentRepository.existsByTeamMemberUserUuid(teamMemberUuid)) {
            return true;
        }
        return assignmentRepository.existsByTeamMemberUserUuidAndEventId(teamMemberUuid, eventId);
    }

    private List<UUID> assignedEventIds(UUID teamMemberUuid) {
        return assignmentRepository.findByTeamMemberUserUuid(teamMemberUuid).stream()
                .map(TeamMemberEventAssignment::getEventId)
                .toList();
    }

    private User loadMemberOwnedByCaller(UUID teamMemberUuid, UUID organizerUuid) {
        User member = userRepository.findByUserUuid(teamMemberUuid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team member not found"));
        if (!member.hasRole(User.Role.TEAM_MEMBER)
                || !organizerUuid.equals(member.getCreatedByOrganizerUuid())) {
            // 404 instead of 403 — don't disclose existence of team members
            // belonging to other organizers.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team member not found");
        }
        return member;
    }

    private User requireOrganizerCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        User caller = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Caller not found"));
        if (!caller.hasRole(User.Role.EVENT_ORGANIZER)) {
            // Belt-and-braces — the controller's @PreAuthorize already
            // enforces this, but a service-level recheck keeps the
            // ownership invariant honest if the service is ever called
            // from another code path.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only EVENT_ORGANIZER may manage team members");
        }
        // Cross-check against the JWT's organizerUuid claim — for an
        // EVENT_ORGANIZER it should equal their own user_uuid. A mismatch
        // would mean the token was minted before the V20 migration or by
        // a buggy code path; fail loudly rather than silently fall back
        // to the DB value.
        UUID claimUuid = AuthenticatedCaller.organizerUuid(auth);
        if (claimUuid != null && !claimUuid.equals(caller.getUserUuid())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "JWT organizerUuid does not match caller — please log in again");
        }
        return caller;
    }

    /**
     * Multi-channel credential delivery for a freshly-onboarded team member.
     *
     * <p>Fires <b>email AND WhatsApp in parallel</b> (the two zero-marginal-cost
     * channels) and only falls back to <b>SMS</b> when BOTH fail. The rationale:
     * deliverability for gate-staff onboarding can't depend on a single
     * provider (Gmail spam-flagging the InnBucks domain, a WhatsApp gateway
     * rotation), but we don't want to spend on SMS when at least one free
     * channel succeeded. Worst case: 1 email + 1 WhatsApp + 1 SMS. Best case:
     * 1 email + 1 WhatsApp, no SMS.
     *
     * <p>Best-effort: a delivery failure on EVERY channel logs loudly but never
     * rolls the account back — the temporary password can be re-issued via a
     * separate reset flow if needed. Never logs the password itself.
     *
     * <p>The two parallel calls run on the common ForkJoin pool — the call
     * volume is low (organizer onboards staff manually, not at scale) and each
     * call already has a 10s read timeout inside its gateway client, so no
     * dedicated executor is warranted.
     */
    private void notifyOnboarding(User member, String tempPassword) {
        String roleLabel = "Team Member";
        String email = member.getEmail();
        String phone = member.getPhoneNumber();
        String ref = "TEAM-ONBOARD-" + member.getId();
        String firstName = member.getFirstName();
        String htmlBody = buildOnboardingHtml(firstName, email, roleLabel, tempPassword);
        String plainBody = buildOnboardingPlainText(email, tempPassword);

        // Fire the two free channels concurrently.
        CompletableFuture<Boolean> emailFuture = CompletableFuture.supplyAsync(
                () -> trySendEmail(member, email, htmlBody, ref));
        CompletableFuture<Boolean> whatsappFuture = CompletableFuture.supplyAsync(
                () -> trySendWhatsapp(member, phone, plainBody));

        CompletableFuture.allOf(emailFuture, whatsappFuture).join();
        boolean emailOk = emailFuture.join();
        boolean whatsappOk = whatsappFuture.join();

        if (emailOk || whatsappOk) {
            log.info("Onboarding delivered userId={} email={} whatsapp={}",
                    member.getId(), emailOk, whatsappOk);
            return;
        }

        // Both free channels failed — fall back to SMS.
        if (phone == null || phone.isBlank()) {
            log.warn("Onboarding email + WhatsApp both failed and no phone on file " +
                    "(account still created) userId={}", member.getId());
            return;
        }
        try {
            smsNotificationClient.sendSms(phone, plainBody, ref);
            log.info("Onboarding SMS fallback delivered userId={}", member.getId());
        } catch (RuntimeException smsEx) {
            log.warn("Onboarding failed on all channels (email, WhatsApp, SMS); " +
                    "account still created userId={} smsError={}",
                    member.getId(), smsEx.getMessage());
        }
    }

    /** Returns true iff the email was accepted by the gateway. Skipped (false)
     *  when the member has no email on file; that's reported once via the
     *  aggregate log line, not here. */
    private boolean trySendEmail(User member, String email, String htmlBody, String ref) {
        if (email == null || email.isBlank()) {
            return false;
        }
        try {
            emailNotificationClient.sendEmail(
                    email,
                    "Welcome to InnBucks — your team-member account is ready",
                    htmlBody,
                    ref);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Onboarding email failed userId={} reason={}", member.getId(), ex.getMessage());
            return false;
        }
    }

    /** Returns true iff WhatsApp accepted the message. Skipped (false) when
     *  the member has no phone on file. */
    private boolean trySendWhatsapp(User member, String phone, String plainBody) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        try {
            whatsAppNotificationClient.sendCustomNotification(phone, plainBody);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Onboarding WhatsApp failed userId={} reason={}", member.getId(), ex.getMessage());
            return false;
        }
    }

    /** Single SMS / WhatsApp body — same wording on both, kept short so SMS
     *  segments don't multiply needlessly. */
    private String buildOnboardingPlainText(String email, String tempPassword) {
        String account = (email != null && !email.isBlank()) ? " (" + email + ")" : "";
        return "Welcome to InnBucks. Your team-member account" + account
                + " is ready. Temporary password: " + tempPassword
                + ". Log in and change it immediately.";
    }

    private String buildOnboardingHtml(String firstName, String email, String roleLabel, String tempPassword) {
        String name = (firstName != null && !firstName.isBlank())
                ? HtmlUtils.htmlEscape(firstName) : "there";
        return "<p>Hi " + name + ",</p>"
                + "<p>An InnBucks account has been created for you as a <strong>"
                + roleLabel + "</strong>.</p>"
                + "<p>Use these credentials to sign in to the scanner app:</p>"
                + "<ul>"
                + "<li><strong>Username:</strong> " + HtmlUtils.htmlEscape(email) + "</li>"
                + "<li><strong>Temporary password:</strong> " + HtmlUtils.htmlEscape(tempPassword) + "</li>"
                + "</ul>"
                + "<p>For your security, please log in and change your password immediately.</p>"
                + "<p>— The InnBucks Team</p>";
    }

    private ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
