package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.event.UserDeactivatedEvent;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Service-layer home for SUPER_ADMIN-scoped user-administration operations.
 *
 * <p>Pre-refactor AdminUserController#updateActiveStatus did its own
 * {@code userRepository.findById(id).orElseThrow(() -> new RuntimeException("User not found: " + id))}
 * inline — bypassing the service layer every other controller routes
 * through, surfacing a 400 instead of 404 on misses, and missing the
 * @Transactional boundary that pairs the read + write into one
 * commit. Moved here so the controller can stay thin (translate HTTP <->
 * DTO, nothing else) and so future admin operations don't keep
 * reinventing the pattern.
 *
 * <p>Credential delivery used to run inline here, holding the @Transactional
 * open through three sequential outbound HTTP calls (email -> SMS -> WhatsApp)
 * and stretching the admin {@code PUT /admin/users/{id}/active} response out
 * to 30–48s — past the FE's AbortController, surfacing as a misleading
 * "Request timeout" while the DB had already committed. We now publish a
 * {@link CredentialDeliveryRequested} event and a {@code @TransactionalEventListener
 * (AFTER_COMMIT) + @Async} listener handles the fan-out off the request
 * thread (see {@code notification.CredentialDeliveryListener}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.innbucks.userservice.repository.TenantProfileRepository tenantProfiles;
    /**
     * Cross-service session-supersession publisher (OWASP A07 / CWE-613), needed
     * by {@link #setRoles} so a demotion takes effect fleet-wide immediately.
     * AuthService field-injects this one instead, purely so its many test
     * construction sites don't widen; this class has a single construction site,
     * so a plain constructor dependency is both clearer and easier to assert on.
     */
    private final com.innbucks.userservice.security.TokenVersionPublisher tokenVersionPublisher;

    /**
     * Backward-compatible overload used by unit tests / callers that don't have
     * an HTTP request context. The caller's identity is recorded as "SYSTEM"
     * and the audit row carries no IP / user-agent.
     */
    public User setActive(Long id, boolean active) {
        return setActive(id, active, null, AuditContext.none());
    }

    @Transactional
    public User setActive(Long id, boolean active, String adminEmail, AuditContext auditContext) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));

        // SUPER_ADMIN is the platform-owner account, seeded once by
        // BOOTSTRAP_ADMIN_PASSWORD and never modified through the admin API.
        // Disabling it would lock the platform out of itself (no other role
        // can re-enable). 403 (not 400) — this isn't a malformed request,
        // it's an action no caller is permitted to take, ever.
        if (user.hasRole(User.Role.SUPER_ADMIN)) {
            log.warn("setActive refused on SUPER_ADMIN target userId={} by={} attemptedActive={}",
                    id, adminEmail == null ? "system" : adminEmail, active);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The SUPER_ADMIN account cannot be activated or deactivated.");
        }

        // The first activation of an account is its approval: registration left
        // only an unusable placeholder password, so assign the default now and
        // force a change on first login. The `approved` flag makes this a
        // one-shot — a later deactivate/reactivate must never reset a password
        // the user has since changed.
        boolean firstApproval = active && !user.isApproved();

        // Retry semantics: if the row already shows the requested state AND it
        // isn't a still-pending first approval, only treat as a no-op when the
        // previous credential delivery actually reached the user. The original
        // incident showed a user stuck "approved but unreachable" because every
        // channel failed and the retry button hit this short-circuit without
        // re-firing. Now: if no channel ever confirmed delivery, we rotate the
        // temp password and re-publish.
        if (user.isActive() == active && !firstApproval) {
            boolean deliveryStillPending = active
                    && user.isMustChangePassword()
                    && user.getCredentialDeliveredAt() == null;
            if (!deliveryStillPending) {
                log.info("setActive no-op userId={} active={}", id, active);
                return user;
            }
            String fresh = TemporaryPasswordGenerator.generate();
            user.setPassword(passwordEncoder.encode(fresh));
            user.setMustChangePassword(true);
            User saved = userRepository.save(user);
            log.info("setActive retry re-publishing credential delivery userId={} "
                    + "(previous attempt left credential_delivered_at NULL)", id);
            publishCredentialDelivery(saved, fresh, CredentialDeliveryRequested.Reason.APPROVAL);
            return saved;
        }

        // The generated temp password (firstApproval only) has to survive past
        // the save so it can be delivered to the user — it's never persisted in
        // plaintext, only the bcrypt hash is.
        String tempPassword = null;
        if (firstApproval) {
            tempPassword = TemporaryPasswordGenerator.generate();
            user.setPassword(passwordEncoder.encode(tempPassword));
            user.setMustChangePassword(true);
            user.setApproved(true);
            log.info("User approved, temporary password assigned userId={}", id);
        }

        user.setActive(active);
        User saved = userRepository.save(user);
        log.info("User {} userId={}", active ? "activated" : "deactivated", id);

        recordAudit(saved, firstApproval, active, adminEmail, auditContext);

        if (firstApproval) {
            publishCredentialDelivery(saved, tempPassword,
                    CredentialDeliveryRequested.Reason.APPROVAL);
        } else if (!active) {
            // Off-thread (AFTER_COMMIT + @Async listener) so a slow notification
            // gateway can't stall — or time out — the deactivate response after
            // the row has committed. Mirrors the credential-delivery path.
            eventPublisher.publishEvent(new UserDeactivatedEvent(
                    saved.getId(), saved.getFirstName(), saved.getEmail(),
                    saved.getPhoneNumber(), saved.hasRole(User.Role.CUSTOMER)));
        }
        return saved;
    }

    /**
     * Callback target for {@code CredentialDeliveryListener} — marks the moment
     * any channel (email / SMS / WhatsApp) confirmed delivery so a retried
     * activation knows not to re-fire. Runs in its own transaction because the
     * listener executes after the original setActive() transaction has already
     * committed (and on a different thread, thanks to @Async). Failure to mark
     * is non-fatal (the timestamp is a UX hint, not an invariant) so a transient
     * DB hiccup here doesn't blow up the listener thread; logged at WARN.
     *
     * <p>The write is a targeted single-column UPDATE, NOT a load-mutate-save.
     * Because this runs on a background thread an unbounded time after the
     * activation committed, a read-modify-write here races every other writer
     * of the row: {@link User} has no {@code @DynamicUpdate}, so {@code save}
     * would rewrite every column from a snapshot that may already be stale, and
     * a password change or admin edit made in that window would be silently
     * reverted. This surfaced as a flaky {@code AuthControllerIT} failure — the
     * test set a known password right after approval and the listener put the
     * random temp password back underneath it, so login 400'd on credentials
     * that had just been written — but the same lost update is reachable in
     * production by any user who changes their password shortly after approval.
     */
    @Transactional
    public void markCredentialDelivered(Long userId) {
        if (userRepository.markCredentialDelivered(userId, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
            log.warn("markCredentialDelivered: user vanished between event publish "
                    + "and listener callback userId={}", userId);
        }
    }

    /**
     * Mint a fresh temporary password for an already-onboarded system user and
     * re-deliver it. This is the recovery path for when the original onboarding
     * notification never reached the user — with per-user random passwords (vs.
     * the old shared {@code #Pass123}) the notification is the ONLY channel that
     * carries the credential, so a SUPER_ADMIN needs a way to re-issue it.
     *
     * <p>The old password is irretrievably bcrypt-hashed, so "resend" can only
     * mean "generate a new one" — that's why this rotates rather than re-sends.
     * The user is re-flagged {@code mustChangePassword} so the fresh value is
     * still single-use. {@code credentialDeliveredAt} is cleared so a successful
     * delivery on this fresh password updates the timestamp cleanly.
     *
     * <p>Refuses to act on a SUPER_ADMIN target: that account's credential is
     * owned by the {@code BOOTSTRAP_ADMIN_PASSWORD} env seed, not this
     * notification-delivered flow. Resetting it would lock the platform owner
     * out behind an SMS/email that may never arrive.
     */
    @Transactional
    public User resetTemporaryPassword(Long id, String adminEmail, AuditContext auditContext) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));

        if (user.hasRole(User.Role.SUPER_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot reset the temporary password of a SUPER_ADMIN; that credential is "
                            + "managed via BOOTSTRAP_ADMIN_PASSWORD");
        }

        String tempPassword = TemporaryPasswordGenerator.generate();
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setMustChangePassword(true);
        user.setCredentialDeliveredAt(null);
        User saved = userRepository.save(user);
        log.info("Temporary password reset userId={} by={}", id, adminEmail == null ? "system" : adminEmail);

        auditService.recordSuccess(
                AuditEventType.USER_TEMP_PASSWORD_RESET,
                adminEmail == null ? "system" : adminEmail,
                adminEmail == null ? AuditService.ACTOR_TYPE_SYSTEM : AuditService.ACTOR_TYPE_USER,
                String.valueOf(saved.getId()), AuditService.TARGET_TYPE_USER,
                Map.of("targetEmail", saved.getEmail() == null ? "" : saved.getEmail()),
                auditContext == null ? AuditContext.none() : auditContext);

        publishCredentialDelivery(saved, tempPassword, CredentialDeliveryRequested.Reason.RESET);
        return saved;
    }

    /**
     * Replace a user's entire role set (SUPER_ADMIN-only, {@code PUT /admin/users/{id}/roles}).
     *
     * <p><b>Replace, not merge.</b> The submitted set becomes the account's
     * complete role set. A merge endpoint can only ever add privilege, so
     * revoking one would need a second endpoint and the two would drift; one
     * idempotent replace keeps the surface honest — the caller states the end
     * state they want and gets exactly it.
     *
     * <p><b>The token bump is the point, not a side-effect.</b> Roles are baked
     * into the JWT at login, and {@code JwtFilter} authorizes from the token's
     * claims — not from a per-request DB read. So demoting a user in Postgres
     * alone leaves their existing access token carrying the OLD roles until it
     * expires: a revoked MERCHANT_ADMIN would keep acting as one for the rest of
     * the token's life. Bumping {@code token_version} (and mirroring it to the
     * shared Redis, exactly as logout does) makes every service reject that token
     * on the next request, so the demotion is effective immediately. The user
     * re-authenticates — or silently rotates via {@code /auth/refresh}, which
     * rebuilds claims from this freshly-written row — and picks up the new roles.
     *
     * <p>Refuses to touch a SUPER_ADMIN target and refuses to GRANT SUPER_ADMIN.
     * The first would let an admin strip the platform-owner account (which no
     * other role can restore); the second would turn this endpoint into a
     * self-service privilege-escalation path — any SUPER_ADMIN could mint more
     * of them, and the seeded owner would lose its "one account, one credential,
     * managed by BOOTSTRAP_ADMIN_PASSWORD" property.
     */
    @Transactional
    public User setRoles(Long id, Set<User.Role> roles, String adminEmail, AuditContext auditContext) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));

        // Normalize the input up front: copy into an EnumSet, tolerating a null
        // from a programmatic caller, and validate the COPY. Everything below
        // then reads one well-typed set instead of the raw argument.
        // (EnumSet.copyOf(Collection) can't do this — it throws on an empty
        // non-EnumSet argument, which is precisely the case being rejected.)
        //
        // Bean validation (@NotEmpty) already covers the HTTP path; the empty
        // check keeps the invariant for any programmatic caller. An account with
        // no roles could authenticate but authorize for nothing — a silent
        // brick, not a state any caller means to reach.
        Set<User.Role> requested = EnumSet.noneOf(User.Role.class);
        if (roles != null) requested.addAll(roles);
        if (requested.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "roles must contain at least one role");
        }

        if (user.hasRole(User.Role.SUPER_ADMIN)) {
            log.warn("setRoles refused on SUPER_ADMIN target userId={} by={} requested={}",
                    id, adminEmail == null ? "system" : adminEmail, requested);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The SUPER_ADMIN account's roles cannot be changed.");
        }

        if (requested.contains(User.Role.SUPER_ADMIN)) {
            log.warn("setRoles refused SUPER_ADMIN grant userId={} by={}",
                    id, adminEmail == null ? "system" : adminEmail);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPER_ADMIN cannot be granted through this endpoint; that account is seeded "
                            + "once via BOOTSTRAP_ADMIN_PASSWORD.");
        }

        // Scope guards. SHOP_ADMIN / SHOP_USER authorize off the loyaltyShopId
        // baked into their JWT, and TEAM_MEMBER off its parent organizer's uuid.
        // Granting one of those roles to an account that was never stamped with
        // the matching scope mints a login that passes @PreAuthorize and then
        // fails inside every handler ("caller's JWT has no shopId") — a broken
        // account that looks correctly provisioned. Refuse up front and name the
        // endpoint that does the stamping.
        if ((requested.contains(User.Role.SHOP_ADMIN) || requested.contains(User.Role.SHOP_USER))
                && (user.getLoyaltyMerchantId() == null || user.getLoyaltyShopId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SHOP_ADMIN and SHOP_USER require the account to be scoped to a loyalty "
                            + "merchant and shop; create shop staff via POST /admin/shop-staff/admins "
                            + "or POST /admin/shop-staff/users instead.");
        }
        if (requested.contains(User.Role.TEAM_MEMBER) && user.getCreatedByOrganizerUuid() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "TEAM_MEMBER requires the account to be stamped with its parent EVENT_ORGANIZER; "
                            + "create team members via POST /event-organizer/team-members instead.");
        }

        // EnumSet.copyOf(Collection) throws on an empty non-EnumSet argument, so
        // build the copies by hand — `previous` is legitimately empty on a row
        // that carries no roles, and that must not blow up the audit.
        //
        // No null-guard on getRoles(): the field is initialised at its
        // declaration (@Builder.Default EnumSet.noneOf) and Hibernate always
        // injects a collection wrapper for an @ElementCollection, so it is
        // non-null on every path into here. Qodana flagged the guard as dead.
        Set<User.Role> current = user.getRoles();
        Set<User.Role> previous = EnumSet.noneOf(User.Role.class);
        previous.addAll(current);

        // Idempotent: re-submitting the current set is a no-op. Skipping the
        // token bump here matters — otherwise a UI that PUTs on every save would
        // log the user out for a change that didn't happen.
        if (previous.equals(requested)) {
            log.info("setRoles no-op userId={} roles={}", id, requested);
            return user;
        }

        // Mutate the mapped collection in place rather than swapping the
        // reference: `roles` is an @ElementCollection, and Hibernate tracks the
        // instance it loaded.
        current.clear();
        current.addAll(requested);
        user.setTokenVersion(user.getTokenVersion() + 1);
        User saved = userRepository.save(user);

        // Best-effort, never throws — Postgres stays the source of truth and
        // user-service's own JwtFilter reads token_version from it directly.
        tokenVersionPublisher.publish(saved.getUserUuid(), saved.getTokenVersion());

        log.info("Roles changed userId={} from={} to={} newTokenVersion={} by={}",
                id, previous, requested, saved.getTokenVersion(),
                adminEmail == null ? "system" : adminEmail);

        auditService.recordSuccess(
                AuditEventType.USER_ROLES_CHANGED,
                adminEmail == null ? "system" : adminEmail,
                adminEmail == null ? AuditService.ACTOR_TYPE_SYSTEM : AuditService.ACTOR_TYPE_USER,
                String.valueOf(saved.getId()), AuditService.TARGET_TYPE_USER,
                Map.of(
                        "targetEmail", saved.getEmail() == null ? "" : saved.getEmail(),
                        "previousRoles", previous.stream().map(Enum::name).sorted().toList(),
                        "newRoles", requested.stream().map(Enum::name).sorted().toList()),
                auditContext == null ? AuditContext.none() : auditContext);

        return saved;
    }

    /**
     * Append an audit row covering the SUPER_ADMIN actor + the user whose
     * status changed. AuditService runs in REQUIRES_NEW + swallows exceptions
     * so a transient DB hiccup on the audit path can't reject the
     * already-committed activation; operators reading logs see the
     * {@code AUDIT_WRITE_FAILED} marker.
     */
    private void recordAudit(User target, boolean firstApproval, boolean active,
                             String adminEmail, AuditContext auditContext) {
        AuditEventType type = firstApproval
                ? AuditEventType.USER_APPROVED
                : (active ? AuditEventType.USER_ACTIVATED : AuditEventType.USER_DEACTIVATED);
        // adminEmail null when called via the no-arg overload (background /
        // tests). Surface that as actor_type=SYSTEM so the row still lands.
        String actorId = adminEmail == null ? "system" : adminEmail;
        String actorType = adminEmail == null
                ? AuditService.ACTOR_TYPE_SYSTEM
                : AuditService.ACTOR_TYPE_USER;
        auditService.recordSuccess(
                type,
                actorId, actorType,
                String.valueOf(target.getId()), AuditService.TARGET_TYPE_USER,
                Map.of(
                        "targetEmail", target.getEmail() == null ? "" : target.getEmail(),
                        "active", active,
                        "mustChangePassword", target.isMustChangePassword()),
                auditContext == null ? AuditContext.none() : auditContext);
    }

    /** Fires the event the async listener picks up after this transaction commits. */
    private void publishCredentialDelivery(User user, String tempPassword,
                                           CredentialDeliveryRequested.Reason reason) {
        // Name the business in the copy when the account has a tenant profile:
        // "Your Fast Jet tenant account has been approved" tells the recipient
        // which of their accounts this is, where the generic wording does not.
        String organisation = tenantProfiles.findByUserId(user.getId())
                .map(com.innbucks.userservice.entity.TenantProfile::getBusinessName)
                // No null check needed: Optional.map already yields empty when
                // getBusinessName() returns null, so only blankness is left to
                // screen out.
                .filter(n -> !n.isBlank())
                .orElse(null);
        eventPublisher.publishEvent(new CredentialDeliveryRequested(
                user.getId(), user.getFirstName(), user.getEmail(),
                user.getPhoneNumber(), tempPassword, reason, organisation));
    }
}
