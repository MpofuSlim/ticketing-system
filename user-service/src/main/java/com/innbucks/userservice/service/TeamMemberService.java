package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CreateTeamMemberDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.TeamMemberEventAssignment;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.TeamMemberEventAssignmentRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.AuthenticatedCaller;
import com.innbucks.userservice.util.HtmlSanitizer;
import com.innbucks.userservice.util.MsisdnValidator;
import com.innbucks.userservice.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

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
    private final ApplicationEventPublisher eventPublisher;

    /** Deployment country pin. Team members are anchored to this cell — see
     *  {@link ShopStaffService#deploymentCountry} for the reasoning. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    @Transactional
    public UserResponseDTO createTeamMember(CreateTeamMemberDTO req) {
        User caller = requireOrganizerCaller();
        UUID organizerUuid = caller.getUserUuid();

        if (userRepository.existsByEmail(req.getEmail())) {
            throw badRequest("Email already registered");
        }
        // Canonicalise to E.164 against this cell's country before the
        // uniqueness check and before storing — one format everywhere.
        String normalizedPhone = MsisdnValidator.normalizeToE164(req.getPhoneNumber(), deploymentCountry)
                .orElseThrow(() -> badRequest("Invalid phone number: " + req.getPhoneNumber()));
        if (userRepository.existsByPhoneNumberAndHomeCountry(normalizedPhone, deploymentCountry)) {
            throw badRequest("Phone number already registered");
        }

        String tempPassword = TemporaryPasswordGenerator.generate();
        User member = User.builder()
                // Strip HTML from the free-text name fields before persisting
                // (OWASP A03 / stored-XSS).
                .firstName(HtmlSanitizer.stripAll(req.getFirstName()))
                .middleName(HtmlSanitizer.stripAll(req.getMiddleName()))
                .lastName(HtmlSanitizer.stripAll(req.getLastName()))
                .email(req.getEmail())
                .phoneNumber(normalizedPhone)
                .homeCountry(deploymentCountry)
                .password(passwordEncoder.encode(tempPassword))
                .roles(EnumSet.of(User.Role.TEAM_MEMBER))
                // Team members operate the ticketing scanner; grant the
                // ticketing bundle so their JWT carries the same services
                // claim an organizer would.
                .defaultServices(new LinkedHashSet<>(List.of(Services.TICKETING)))
                .active(true)
                // Created directly by an EVENT_ORGANIZER — no SUPER_ADMIN approval
                // step, so they are approved on creation. Set it explicitly so
                // login's pending-approval check never treats a team member as a
                // pending registration.
                .approved(true)
                .createdByOrganizerUuid(organizerUuid)
                .build();
        userRepository.save(member);
        log.info("Created TEAM_MEMBER userId={} userUuid={} organizerUuid={} by={}",
                member.getId(), member.getUserUuid(), organizerUuid, caller.getEmail());
        // Deliver credentials OFF the request thread via the shared async
        // CredentialDeliveryListener (email -> SMS -> WhatsApp, AFTER_COMMIT,
        // best-effort). The old path blocked the request thread on
        // CompletableFuture.join(), so a slow gateway stalled the create response.
        eventPublisher.publishEvent(new CredentialDeliveryRequested(
                member.getId(), member.getFirstName(), member.getEmail(), member.getPhoneNumber(),
                tempPassword, CredentialDeliveryRequested.Reason.ONBOARDING));
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

        eventPublisher.publishEvent(new CredentialDeliveryRequested(
                member.getId(), member.getFirstName(), member.getEmail(), member.getPhoneNumber(),
                tempPassword, CredentialDeliveryRequested.Reason.RESET));
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

    /** True iff the current request is from a user with {@code ROLE_SUPER_ADMIN}.
     *  {@code getAuthorities()} is @NonNull on Spring's AbstractAuthenticationToken
     *  (defaults to AuthorityUtils.NO_AUTHORITIES), so only the outer auth check
     *  matters. */
    private boolean isCallerSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
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
        if (auth == null) return "unknown";
        // getName() returns "" (never null) on AbstractAuthenticationToken when
        // the principal is unset, so no inner null guard is needed.
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

    private ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
