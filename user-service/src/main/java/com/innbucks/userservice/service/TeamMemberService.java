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
import org.springframework.security.core.GrantedAuthority;
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
        if (isCallerSuperAdmin()) {
            // SUPER_ADMIN sees every TEAM_MEMBER across all organizers — they
            // run support / oversight, not a single tenant. Returns an empty
            // list (never 403) when no team members exist anywhere.
            return userRepository.findByAnyRole(List.of(User.Role.TEAM_MEMBER)).stream()
                    .map(UserResponseDTO::from)
                    .toList();
        }
        User caller = requireOrganizerCaller();
        return userRepository.findByCreatedByOrganizerUuid(caller.getUserUuid()).stream()
                .map(UserResponseDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponseDTO getMyTeamMember(UUID teamMemberUuid) {
        User member = loadMemberForCaller(teamMemberUuid);
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
        User member = loadMemberForCaller(teamMemberUuid);
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
        log.info("Disabled TEAM_MEMBER userUuid={} by={} revokedRefreshFamilies={}",
                member.getUserUuid(), callerLogTag(), revokedFamilies);
        return UserResponseDTO.from(member);
    }

    @Transactional
    public UserResponseDTO enableTeamMember(UUID teamMemberUuid) {
        User member = loadMemberForCaller(teamMemberUuid);
        if (member.isActive()) {
            return UserResponseDTO.from(member);
        }
        member.setActive(true);
        userRepository.save(member);
        log.info("Re-enabled TEAM_MEMBER userUuid={} by={}",
                member.getUserUuid(), callerLogTag());
        return UserResponseDTO.from(member);
    }

    /**
     * Mints a fresh temporary password for a team member and re-delivers it via
     * the same parallel(email + WhatsApp) → SMS-fallback dispatcher used at
     * onboarding. Use case: the original onboarding notification never reached
     * the member (spam-filtered, wrong number, etc.) and they can't log in.
     *
     * <p>Always flips {@code must_change_password=true} — the new password is
     * a one-time value the member must rotate on first login, regardless of
     * whether they had previously set a real password. Does NOT bump
     * {@code token_version} or revoke refresh families: this matches the
     * SUPER_ADMIN reset path in {@link UserAdminService} and is correct for
     * the common case ("re-send creds because the channel failed"). If you
     * also need to kill outstanding sessions, call {@link #disableTeamMember}
     * first (which does revoke).
     *
     * <p>Best-effort delivery: a triple-channel failure still commits the
     * password rotation (the organizer can re-issue), and the member's old
     * password no longer works. The response never contains the new password
     * itself — it travels only via the notification channels.
     */
    @Transactional
    public UserResponseDTO resetTemporaryPassword(UUID teamMemberUuid) {
        User member = loadMemberForCaller(teamMemberUuid);

        String tempPassword = TemporaryPasswordGenerator.generate();
        member.setPassword(passwordEncoder.encode(tempPassword));
        member.setMustChangePassword(true);
        userRepository.save(member);
        log.info("Reset TEAM_MEMBER temp password userUuid={} by={}",
                member.getUserUuid(), callerLogTag());

        notifyPasswordReset(member, tempPassword);
        return UserResponseDTO.from(member);
    }

    /**
     * Assigns the team member to an event (idempotent). The first assignment
     * for a member flips them from organizer-wide scanning to "assigned events
     * only" — see {@link #canScanEvent}. Returns the member's full current
     * assignment set so the caller can refresh its view in one round trip.
     */
    @Transactional
    public List<UUID> assignEvent(UUID teamMemberUuid, UUID eventId) {
        User member = loadMemberForCaller(teamMemberUuid);
        // Even when SUPER_ADMIN assigns, the assignment row's "assigned by"
        // column reflects the team member's owning organizer — that's the
        // entity who actually owns the relationship, not the admin who acted.
        UUID assignedBy = member.getCreatedByOrganizerUuid();
        if (!assignmentRepository.existsByTeamMemberUserUuidAndEventId(teamMemberUuid, eventId)) {
            assignmentRepository.save(TeamMemberEventAssignment.builder()
                    .teamMemberUserUuid(teamMemberUuid)
                    .eventId(eventId)
                    .assignedByOrganizerUuid(assignedBy)
                    .build());
            log.info("Assigned TEAM_MEMBER userUuid={} to eventId={} by={} ownerOrganizerUuid={}",
                    teamMemberUuid, eventId, callerLogTag(), assignedBy);
        }
        return assignedEventIds(teamMemberUuid);
    }

    /**
     * Removes an event assignment (idempotent). If this was the member's last
     * assignment they revert to organizer-wide scanning (no rows = wide open).
     */
    @Transactional
    public List<UUID> unassignEvent(UUID teamMemberUuid, UUID eventId) {
        loadMemberForCaller(teamMemberUuid);
        long removed = assignmentRepository.deleteByTeamMemberUserUuidAndEventId(teamMemberUuid, eventId);
        if (removed > 0) {
            log.info("Unassigned TEAM_MEMBER userUuid={} from eventId={} by={}",
                    teamMemberUuid, eventId, callerLogTag());
        }
        return assignedEventIds(teamMemberUuid);
    }

    @Transactional(readOnly = true)
    public List<UUID> listAssignedEvents(UUID teamMemberUuid) {
        loadMemberForCaller(teamMemberUuid);
        return assignedEventIds(teamMemberUuid);
    }

    /**
     * Scan-time authorization data for booking-service (called S2S, NOT through
     * an organizer session — so no ownership check here; the caller is trusted
     * via X-Internal-Token). Implements <b>deny-by-default</b>: a TEAM_MEMBER
     * may scan ONLY the events explicitly assigned to them via {@link
     * #assignEvent}. A member with no assignment rows can scan nothing — the
     * organizer must assign at least one event for them to be useful at the
     * gate.
     *
     * <p>EVENT_ORGANIZERs never reach this method — booking-service's
     * {@code TicketScanService} bypasses the assignment check entirely for the
     * organizer role.
     */
    @Transactional(readOnly = true)
    public boolean canScanEvent(UUID teamMemberUuid, UUID eventId) {
        return assignmentRepository.existsByTeamMemberUserUuidAndEventId(teamMemberUuid, eventId);
    }

    /**
     * Raw list of every event_id a team member is assigned to. S2S surface
     * used by event-service to filter a team member's {@code /events/my}
     * response to just the events they may scan. No ownership check —
     * trusted via X-Internal-Token.
     */
    @Transactional(readOnly = true)
    public List<UUID> assignedEventIdsFor(UUID teamMemberUuid) {
        return assignedEventIds(teamMemberUuid);
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

    /**
     * Caller-aware loader for read + lifecycle endpoints. SUPER_ADMIN skips
     * the per-organizer ownership check entirely (oversight role — they need
     * to see / act on every team member). EVENT_ORGANIZERs still get 404 for
     * members they don't own, so we never disclose another organizer's staff.
     */
    private User loadMemberForCaller(UUID teamMemberUuid) {
        if (isCallerSuperAdmin()) {
            User member = userRepository.findByUserUuid(teamMemberUuid)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team member not found"));
            if (!member.hasRole(User.Role.TEAM_MEMBER)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team member not found");
            }
            return member;
        }
        User caller = requireOrganizerCaller();
        return loadMemberOwnedByCaller(teamMemberUuid, caller.getUserUuid());
    }

    /** True iff the current request is from a user with {@code ROLE_SUPER_ADMIN}. */
    private boolean isCallerSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Human-readable identifier for the caller in log lines — admin email when
     * the caller is SUPER_ADMIN, organizer email otherwise. Used so the audit
     * trail tells the operator who actually triggered a team-member change,
     * not just the team member's owning organizer.
     */
    private String callerLogTag() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return "unknown";
        return isCallerSuperAdmin() ? "admin:" + auth.getName() : auth.getName();
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
        deliverCredentials(member, tempPassword,
                "Welcome to InnBucks — your team-member account is ready",
                buildOnboardingHtml(member.getFirstName(), member.getEmail(), tempPassword),
                buildOnboardingPlainText(member.getEmail(), tempPassword),
                "TEAM-ONBOARD-" + member.getId(),
                "Onboarding");
    }

    /** Reset variant of {@link #notifyOnboarding}. Same multi-channel dispatch,
     *  different wording so the recipient understands this is a re-issue. */
    private void notifyPasswordReset(User member, String tempPassword) {
        deliverCredentials(member, tempPassword,
                "Your InnBucks team-member password has been reset",
                buildResetHtml(member.getFirstName(), member.getEmail(), tempPassword),
                buildResetPlainText(member.getEmail(), tempPassword),
                "TEAM-RESET-" + member.getId() + "-" + System.currentTimeMillis(),
                "Password reset");
    }

    /**
     * Generic credential dispatcher used by both onboarding and password-reset.
     * Fires <b>email AND WhatsApp in parallel</b> and only falls back to
     * <b>SMS</b> when BOTH fail. Best-effort: a triple failure logs loudly but
     * never rolls back the underlying state change (account creation / password
     * update); the password can always be re-issued. Never logs the password.
     *
     * <p>The two parallel calls run on the common ForkJoin pool — call volume
     * is low (manual organizer ops) and each gateway client enforces a 10s
     * read timeout, so no dedicated executor is warranted.
     */
    private void deliverCredentials(User member, String tempPassword,
                                    String subject, String htmlBody, String plainBody,
                                    String ref, String logTag) {
        String phone = member.getPhoneNumber();

        CompletableFuture<Boolean> emailFuture = CompletableFuture.supplyAsync(
                () -> trySendEmail(member, subject, htmlBody, ref, logTag));
        CompletableFuture<Boolean> whatsappFuture = CompletableFuture.supplyAsync(
                () -> trySendWhatsapp(member, phone, plainBody, logTag));

        CompletableFuture.allOf(emailFuture, whatsappFuture).join();
        boolean emailOk = emailFuture.join();
        boolean whatsappOk = whatsappFuture.join();

        if (emailOk || whatsappOk) {
            log.info("{} delivered userId={} email={} whatsapp={}",
                    logTag, member.getId(), emailOk, whatsappOk);
            return;
        }
        if (phone == null || phone.isBlank()) {
            log.warn("{} email + WhatsApp both failed and no phone on file " +
                    "(state change still committed) userId={}", logTag, member.getId());
            return;
        }
        try {
            smsNotificationClient.sendSms(phone, plainBody, ref);
            log.info("{} SMS fallback delivered userId={}", logTag, member.getId());
        } catch (RuntimeException smsEx) {
            log.warn("{} failed on all channels (email, WhatsApp, SMS); state change still " +
                    "committed userId={} smsError={}",
                    logTag, member.getId(), smsEx.getMessage());
        }
    }

    /** Returns true iff the email was accepted by the gateway. Skipped (false)
     *  when the member has no email on file; that's reported once via the
     *  aggregate log line, not here. */
    private boolean trySendEmail(User member, String subject, String htmlBody,
                                 String ref, String logTag) {
        String email = member.getEmail();
        if (email == null || email.isBlank()) {
            return false;
        }
        try {
            emailNotificationClient.sendEmail(email, subject, htmlBody, ref);
            return true;
        } catch (RuntimeException ex) {
            log.warn("{} email failed userId={} reason={}", logTag, member.getId(), ex.getMessage());
            return false;
        }
    }

    /** Returns true iff WhatsApp accepted the message. Skipped (false) when
     *  the member has no phone on file. */
    private boolean trySendWhatsapp(User member, String phone, String plainBody, String logTag) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        try {
            whatsAppNotificationClient.sendCustomNotification(phone, plainBody);
            return true;
        } catch (RuntimeException ex) {
            log.warn("{} WhatsApp failed userId={} reason={}", logTag, member.getId(), ex.getMessage());
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

    private String buildOnboardingHtml(String firstName, String email, String tempPassword) {
        String name = (firstName != null && !firstName.isBlank())
                ? HtmlUtils.htmlEscape(firstName) : "there";
        return "<p>Hi " + name + ",</p>"
                + "<p>An InnBucks account has been created for you as a <strong>Team Member</strong>.</p>"
                + "<p>Use these credentials to sign in to the scanner app:</p>"
                + "<ul>"
                + "<li><strong>Username:</strong> " + HtmlUtils.htmlEscape(email) + "</li>"
                + "<li><strong>Temporary password:</strong> " + HtmlUtils.htmlEscape(tempPassword) + "</li>"
                + "</ul>"
                + "<p>For your security, please log in and change your password immediately.</p>"
                + "<p>— The InnBucks Team</p>";
    }

    private String buildResetPlainText(String email, String tempPassword) {
        String account = (email != null && !email.isBlank()) ? " (" + email + ")" : "";
        return "Your InnBucks team-member password" + account
                + " has been reset. New temporary password: " + tempPassword
                + ". Log in and change it immediately. If you didn't request this, contact your organizer.";
    }

    private String buildResetHtml(String firstName, String email, String tempPassword) {
        String name = (firstName != null && !firstName.isBlank())
                ? HtmlUtils.htmlEscape(firstName) : "there";
        return "<p>Hi " + name + ",</p>"
                + "<p>Your InnBucks team-member password has been reset by your organizer.</p>"
                + "<p>Use these credentials to sign in:</p>"
                + "<ul>"
                + "<li><strong>Username:</strong> " + HtmlUtils.htmlEscape(email) + "</li>"
                + "<li><strong>New temporary password:</strong> " + HtmlUtils.htmlEscape(tempPassword) + "</li>"
                + "</ul>"
                + "<p>For your security, please log in and change your password immediately. "
                + "If you didn't request this reset, contact your organizer.</p>"
                + "<p>— The InnBucks Team</p>";
    }

    private ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
