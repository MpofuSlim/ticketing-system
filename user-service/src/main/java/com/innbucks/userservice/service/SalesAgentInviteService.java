package com.innbucks.userservice.service;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.config.SalesAgentInviteProperties;
import com.innbucks.userservice.config.StaffProvisioningCheck;
import com.innbucks.userservice.dto.SalesAgentInviteDTOs;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.SalesAgentInvite;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.AccountSecurityAlertEvent;
import com.innbucks.userservice.event.SalesAgentInviteRequested;
import com.innbucks.userservice.exception.SalesAgentInviteException;
import com.innbucks.userservice.notification.ScannerInviteNotifier;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.SalesAgentInviteRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.SalesAgentInviteTokens;
import com.innbucks.userservice.util.MsisdnMasking;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sales-agent Foundry portal invites (V56).
 *
 * <p><b>Why.</b> A sales agent sells tickets for an organizer from the InnBucks
 * app (their ordinary CUSTOMER session) and from the Foundry portal (email,
 * password, 2FA), with ONE account: the InnBucks account on their phone. The
 * app account has no email and no password anyone knows, so the portal needs a
 * way to bind both to it. event-service mints an invite when the organizer gives
 * a selling team member portal access ({@link #create}); the person opens the
 * emailed link, proves the phone with a code ({@link #sendPhoneCode}) and
 * chooses a password ({@link #accept}).
 *
 * <p><b>What accept does.</b> No account on the phone: one is created, shaped
 * like the InnBucks app's first sign-in ({@code FederatedLoginService}): active,
 * approved, tier-1 profile with the phone verified, placeholder name, plus the
 * invite's email and the chosen password. An account on the phone: it gets the
 * invite's email when it has none, the password, and CUSTOMER if it lacks it.
 * Either way it ends holding CUSTOMER and SALES_AGENT. SALES_AGENT grants no
 * permission; it makes {@code MfaPolicy} treat the portal sign-in as a system
 * user's (forced 2FA), while a phone-proof app session stays CUSTOMER-scoped.
 * What the agent may sell is event-service's live team check, every sale.
 *
 * <p><b>Who can.</b> Proving BOTH halves: the link went to the email, the code
 * goes to the phone. An existing account may become an agent only when it is
 * active, approved, has no staff profile, and every role it holds is CUSTOMER,
 * SALES_AGENT, EVENT_ORGANIZER or MERCHANT_ADMIN ({@link #MAY_BECOME_AGENT}):
 * never a staff role, never shop staff (their account belongs to their
 * merchant, who can reset its password), never a custom role. The rule mirrors
 * {@code FederatedLoginService.mayAlsoBeCustomer}, because accept adds CUSTOMER
 * and so opens the InnBucks app to the account. An account that already has an
 * email keeps it: a different invite email is {@code email_mismatch}, never an
 * overwrite. InnBucks staff domains are reserved ({@code email_domain_reserved}):
 * a staff member sells through their personal account only.
 *
 * <p><b>Order, and what no answer says.</b> The organizer chose the invite's
 * email, so the organizer may hold the link: nothing a caller learns before
 * proving the phone may depend on the account the phone holds (the scanner
 * rule: nothing says whether a number has an InnBucks account). Phone-code
 * checks the link alone and sends the code; accept checks the code BEFORE any
 * refusal about the account or the email ({@code account_not_eligible},
 * {@code email_mismatch}, {@code email_in_use}, a foreign number for a new
 * account), so only the person holding the phone ever reads one. The code is
 * checked in a transaction of its own, so a wrong guess stays counted when
 * accept then fails. Accept locks the account row first, then consumes the
 * invite (the {@code StaffInviteService} order), checks everything again under
 * the lock, and writes the REQUIRED audit last.
 *
 * <p>Never log the token, the link, the code or the password.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesAgentInviteService {

    public static final String NEXT_SIGN_IN = "SIGN_IN";
    public static final String CREATED_MESSAGE = "Portal invite sent";
    public static final String INSPECT_MESSAGE = "Invite is valid";
    public static final String CODE_SENT_MESSAGE = "We sent a code to the phone number on this invite.";
    public static final String ACCEPTED_MESSAGE = "Your sign-in is set up. Sign in to the Foundry portal with your "
            + "email and new password, then set up two-step verification.";

    static final int MAX_ORGANIZER_NAME = 80;
    static final int MAX_EMAIL = 254;
    /** The registration rule: one {@code @}, a dotted domain, no whitespace. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** The placeholder name the app's first sign-in writes ({@code FederatedLoginService.createCustomer}). */
    static final String PLACEHOLDER_FIRST_NAME = "Customer";
    static final String PLACEHOLDER_LAST_NAME = "Pending";

    /** Every role an existing account may hold and still become an agent. */
    static final Set<String> MAY_BECOME_AGENT = Set.of(
            User.Role.CUSTOMER.name(), User.Role.SALES_AGENT.name(),
            User.Role.EVENT_ORGANIZER.name(), User.Role.MERCHANT_ADMIN.name());

    private final SalesAgentInviteRepository invites;
    private final UserRepository users;
    private final CustomerProfileRepository customerProfiles;
    private final RefreshTokenRepository refreshTokens;
    private final StaffEligibility eligibility;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;
    private final TokenVersionBumper tokenVersionBumper;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final SalesAgentInviteProperties properties;
    private final StaffProvisioningCheck provisioning;
    private final CellAffinityChecker cells;

    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    // ------------------------------------------------------------------
    // Internal: event-service → user-service
    // ------------------------------------------------------------------

    /**
     * {@code POST /users/internal/sales-agent-invites}: mints an invite and
     * emails it after commit. A new invite for the same team member revokes its
     * earlier live ones. 400 {@code invalid_request} / {@code invalid_phone} /
     * {@code invalid_email} / {@code email_domain_reserved}; 503
     * {@code sales_agent_invites_unconfigured} when no link can be built or sent.
     * It never says whether the phone or the email has an InnBucks account: the
     * organizer reads this answer.
     */
    @Transactional
    public SalesAgentInviteDTOs.Created create(SalesAgentInviteDTOs.CreateRequest request) {
        UUID teamMemberId = parseUuid(request == null ? null : request.teamMemberId());
        UUID organizerUuid = parseUuid(request == null ? null : request.organizerUuid());
        if (teamMemberId == null || organizerUuid == null) {
            throw SalesAgentInviteException.invalidRequest();
        }
        String phone = MsisdnValidator.normalizeToE164(request.phoneNumber(), deploymentCountry)
                .orElseThrow(SalesAgentInviteException::invalidPhone);
        String email = normaliseEmail(request.email());
        if (email == null) {
            throw SalesAgentInviteException.invalidEmail();
        }
        eligibility.requireEmailNotReserved(email, null, "sales_agent_invite");
        if (!provisioning.invitesProvisioned()) {
            throw SalesAgentInviteException.unconfigured();
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int superseded = invites.revokeLive(teamMemberId, now);
        String rawToken = SalesAgentInviteTokens.generate();
        SalesAgentInvite invite = invites.save(SalesAgentInvite.builder()
                .tokenHash(SalesAgentInviteTokens.hash(rawToken))
                .teamMemberId(teamMemberId)
                .organizerUuid(organizerUuid)
                .organizerName(ScannerInviteNotifier.singleLine(request.organizerName(), MAX_ORGANIZER_NAME))
                .phoneNumber(phone)
                .email(email)
                .createdAt(now)
                .expiresAt(now.plus(properties.getTtl()))
                .build());
        events.publishEvent(new SalesAgentInviteRequested(invite.getId(), email, invite.getOrganizerName(),
                rawToken, invite.getExpiresAt()));
        log.info("Sales-agent invite created inviteId={} teamMemberId={} organizerUuid={} superseded={}",
                invite.getId(), teamMemberId, organizerUuid, superseded);
        return new SalesAgentInviteDTOs.Created(invite.getExpiresAt());
    }

    /**
     * {@code DELETE /users/internal/sales-agent-invites/{teamMemberId}}: every
     * live link of the team member stops working. Never touches an account's
     * roles or password: an agent who already accepted keeps their sign-in, and
     * event-service's live team check is what stops their selling.
     */
    @Transactional
    public SalesAgentInviteDTOs.Revoked revoke(String teamMemberIdRaw) {
        UUID teamMemberId = parseUuid(teamMemberIdRaw);
        if (teamMemberId == null) {
            throw SalesAgentInviteException.invalidRequest();
        }
        int revoked = invites.revokeLive(teamMemberId, LocalDateTime.now(ZoneOffset.UTC));
        log.info("Sales-agent invites revoked teamMemberId={} revoked={}", teamMemberId, revoked);
        return new SalesAgentInviteDTOs.Revoked(revoked);
    }

    // ------------------------------------------------------------------
    // Public: the invite page
    // ------------------------------------------------------------------

    /** Read-only: who the invite is from and for. Does not consume it. One 400 {@code invite_invalid}. */
    @Transactional(readOnly = true)
    public SalesAgentInviteDTOs.Inspection inspect(String rawToken) {
        SalesAgentInvite invite = liveInvite(rawToken);
        return new SalesAgentInviteDTOs.Inspection(invite.getOrganizerName(), invite.getEmail(),
                MsisdnMasking.mask(invite.getPhoneNumber()), invite.getExpiresAt());
    }

    /**
     * Sends the code that proves the invite's phone. Checks the link and
     * nothing else: the answer is the same whatever account the phone holds,
     * because whoever holds the link may be the organizer (see the class
     * comment). No transaction is open while the message is sent
     * ({@code OtpService} commits the code, then sends). Same quota as every
     * other code ({@code otp_retry_attempts}).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendPhoneCode(String rawToken) {
        SalesAgentInvite invite = liveInvite(rawToken);
        otpService.sendSalesAgentInviteCode(invite.getPhoneNumber());
        log.info("Sales-agent invite code sent inviteId={} phone={}", invite.getId(),
                MsisdnMasking.mask(invite.getPhoneNumber()));
    }

    /**
     * Uses the invite up and binds the portal sign-in to the account on the
     * invite's phone. Issues no session: the person signs in next, and sets up
     * 2FA there. See the class comment for the order.
     */
    @Transactional
    public SalesAgentInviteDTOs.Accepted accept(SalesAgentInviteDTOs.AcceptRequest request, AuditContext context) {
        String rawToken = request.token();
        if (!SalesAgentInviteTokens.wellFormed(rawToken)) throw SalesAgentInviteException.inviteInvalid();
        String hash = SalesAgentInviteTokens.hash(rawToken);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        SalesAgentInvite unlocked = invites.findByTokenHash(hash).orElseThrow(SalesAgentInviteException::inviteInvalid);
        if (!unlocked.isLive(now)) {
            recordReplay(unlocked, now, context);
            throw SalesAgentInviteException.inviteInvalid();
        }
        String phone = unlocked.getPhoneNumber();

        // The account row lock FIRST, then the invite (StaffInviteService's
        // order). With no account there is nothing to lock: a concurrent
        // creation on the same phone loses on the unique index (try_again).
        Optional<Long> accountId = users.findByPhoneNumber(phone).map(User::getId);
        Optional<User> locked = accountId.flatMap(users::lockById);
        UUID accountUuid = locked.map(User::getUserUuid).orElseGet(UUID::randomUUID);
        if (invites.consume(hash, accountUuid, now) != 1) {
            invites.findByTokenHash(hash).ifPresent(spent -> recordReplay(spent, now, context));
            throw SalesAgentInviteException.inviteInvalid();
        }
        // consume() cleared the persistence context: read everything again, under the lock.
        SalesAgentInvite invite = invites.findByTokenHash(hash).orElseThrow(SalesAgentInviteException::inviteInvalid);
        User account = null;
        if (accountId.isPresent()) {
            account = users.findById(accountId.get())
                    .filter(u -> phone.equals(u.getPhoneNumber()))
                    .orElseThrow(SalesAgentInviteException::tryAgain);
        }
        // The code BEFORE any refusal about the account or the email, so only
        // the person holding the phone learns one. It commits on its own, so a
        // wrong guess stays counted while everything here rolls back (the
        // invite included).
        if (!otpService.verifySalesAgentInviteCode(phone, request.phoneCode())) {
            log.warn("Sales-agent invite refused: wrong or expired code inviteId={} phone={}", invite.getId(),
                    MsisdnMasking.mask(phone));
            throw SalesAgentInviteException.phoneCodeInvalid();
        }
        Plan plan = assess(invite, account);

        String passwordHash = passwordEncoder.encode(request.password());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("inviteId", invite.getId());
        metadata.put("teamMemberId", String.valueOf(invite.getTeamMemberId()));
        metadata.put("organizerUuid", String.valueOf(invite.getOrganizerUuid()));
        metadata.put("newAccount", account == null);
        User user;
        if (account == null) {
            user = createAccount(accountUuid, phone, invite.getEmail(), passwordHash, now);
            metadata.put("rolesAdded", List.of(User.Role.CUSTOMER.name(), User.Role.SALES_AGENT.name()));
            metadata.put("emailSet", true);
        } else {
            user = account;
            List<String> added = updateAccount(user, plan, invite.getEmail(), passwordHash, now);
            metadata.put("rolesAdded", added);
            metadata.put("emailSet", plan.writesEmail());
            // A password that could sign in before is replaced: end every session
            // (password-reset rule) and tell the person on every channel.
            long version = tokenVersionBumper.bump(user);
            int refreshRevoked = refreshTokens.revokeAllForUser(user.getId(), Instant.now());
            metadata.put("tokenVersion", version);
            metadata.put("refreshTokensRevoked", refreshRevoked);
            events.publishEvent(new AccountSecurityAlertEvent(user.getId(), user.getFirstName(), user.getEmail(),
                    user.getPhoneNumber(), true, AccountSecurityAlertEvent.Type.PASSWORD_CHANGED));
        }
        metadata.put("email", BusinessRegistrationService.maskEmail(user.getEmail()));
        metadata.put("phone", MsisdnMasking.mask(phone));
        users.flush();

        auditService.recordRequired(AuditEventType.SALES_AGENT_PORTAL_ACCESS_GRANTED,
                String.valueOf(user.getUserUuid()), AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getUserUuid()), AuditService.TARGET_TYPE_USER, metadata, context);
        log.info("Sales-agent invite accepted inviteId={} userId={} newAccount={}", invite.getId(), user.getId(),
                account == null);
        return new SalesAgentInviteDTOs.Accepted(user.getEmail(), NEXT_SIGN_IN);
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    /** What accept will write: the invite's email (always, for a new account; only when it has none, for an existing one). */
    record Plan(boolean writesEmail) {}

    /**
     * Every refusal about the account on the phone or the email, in the order
     * the person can act on it; asked only after the code proved the phone.
     * {@code account} null = no account holds the invite's phone.
     */
    Plan assess(SalesAgentInvite invite, User account) {
        String email = invite.getEmail();
        if (account == null) {
            // A new account belongs to this cell's market, like every customer entry point.
            cells.requireDomesticMsisdn(invite.getPhoneNumber());
            requireEmailFree(email, null);
            return new Plan(true);
        }
        if (!mayBecomeAgent(account)) {
            log.info("Sales-agent invite refused: account not eligible userId={}", account.getId());
            throw SalesAgentInviteException.accountNotEligible();
        }
        String current = account.getEmail();
        if (current != null && !current.isBlank()) {
            if (!current.strip().equalsIgnoreCase(email)) {
                log.info("Sales-agent invite refused: the account uses another email userId={}", account.getId());
                throw SalesAgentInviteException.emailMismatch();
            }
            return new Plan(false);
        }
        requireEmailFree(email, account.getId());
        return new Plan(true);
    }

    /** Active, approved, no staff profile, and every role one an agent may hold beside it. */
    boolean mayBecomeAgent(User account) {
        return account.isActive()
                && account.isApproved()
                && account.getRoles() != null
                && !account.getRoles().isEmpty()
                && MAY_BECOME_AGENT.containsAll(account.getRoles())
                && !eligibility.isProfiled(account);
    }

    /** The email is not a staff address, and no OTHER account holds it in any letter case. */
    private void requireEmailFree(String email, Long ownId) {
        eligibility.requireEmailNotReserved(email, null, "sales_agent_invite_accept");
        boolean heldElsewhere = users.findAllByEmailIgnoreCase(email).stream()
                .anyMatch(other -> ownId == null || !ownId.equals(other.getId()));
        if (heldElsewhere) {
            throw SalesAgentInviteException.emailInUse();
        }
    }

    /** The app's first sign-in shape ({@code FederatedLoginService.createCustomer}), plus the email and password. */
    private User createAccount(UUID userUuid, String phone, String email, String passwordHash, LocalDateTime now) {
        User user = User.builder()
                .userUuid(userUuid)
                .firstName(PLACEHOLDER_FIRST_NAME)
                .lastName(PLACEHOLDER_LAST_NAME)
                .phoneNumber(phone)
                .email(email)
                .password(passwordHash)
                .roles(User.roleNames(User.Role.CUSTOMER, User.Role.SALES_AGENT))
                .mfaEnabled(false)
                .active(true)
                .approved(true)
                .homeCountry(cells.homeCountryFor(phone))
                .build();
        try {
            users.saveAndFlush(user);
            customerProfiles.saveAndFlush(newProfile(user, now));
        } catch (DataIntegrityViolationException raced) {
            // Another request created an account on this phone, or took this
            // email, in the meantime. Everything rolls back, the invite included.
            log.warn("Sales-agent invite accept lost a create race phone={}", MsisdnMasking.mask(phone));
            throw SalesAgentInviteException.tryAgain();
        }
        log.info("Customer account created by a sales-agent invite phone={} userId={}", MsisdnMasking.mask(phone),
                user.getId());
        return user;
    }

    /** Email when it has none, the password, CUSTOMER and SALES_AGENT; returns the roles added. */
    private List<String> updateAccount(User user, Plan plan, String email, String passwordHash, LocalDateTime now) {
        if (plan.writesEmail()) {
            user.setEmail(email);
        }
        user.setPassword(passwordHash);
        user.setMustChangePassword(false);
        // They proved the phone and the mailbox: clear a password lockout, as a reset does.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        Set<String> added = new LinkedHashSet<>();
        if (!user.hasRole(User.Role.CUSTOMER)) added.add(User.Role.CUSTOMER.name());
        if (!user.hasRole(User.Role.SALES_AGENT)) added.add(User.Role.SALES_AGENT.name());
        user.getRoles().addAll(added);
        // The mint needs a customer profile for every CUSTOMER; the code just
        // proved the phone, so stamp it as an app sign-in or an OTP does.
        CustomerProfile profile = customerProfiles.findByUserId(user.getId()).orElseGet(() -> newProfile(user, now));
        profile.setPhoneVerified(true);
        profile.setPhoneVerifiedAt(now);
        try {
            users.saveAndFlush(user);
            customerProfiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException raced) {
            log.warn("Sales-agent invite accept lost a race on the account userId={}", user.getId());
            throw SalesAgentInviteException.tryAgain();
        }
        return new ArrayList<>(added);
    }

    private static CustomerProfile newProfile(User user, LocalDateTime now) {
        return CustomerProfile.builder()
                .user(user)
                .registrationTier(1)
                .verified(false)
                .phoneVerified(true)
                .phoneVerifiedAt(now)
                .build();
    }

    /** The live invite for a raw token, or the one opaque 400. */
    private SalesAgentInvite liveInvite(String rawToken) {
        if (!SalesAgentInviteTokens.wellFormed(rawToken)) throw SalesAgentInviteException.inviteInvalid();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return invites.findByTokenHash(SalesAgentInviteTokens.hash(rawToken))
                .filter(i -> i.isLive(now))
                .orElseThrow(SalesAgentInviteException::inviteInvalid);
    }

    /** A used, revoked or expired invite presented to accept again: a FAILURE row, never the token. */
    private void recordReplay(SalesAgentInvite spent, LocalDateTime now, AuditContext context) {
        String state = spent.getUsedAt() != null ? "used"
                : spent.getRevokedAt() != null ? "revoked"
                : !spent.getExpiresAt().isAfter(now) ? "expired" : "unknown";
        log.warn("Sales-agent invite replayed inviteId={} teamMemberId={} state={}", spent.getId(),
                spent.getTeamMemberId(), state);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("inviteId", spent.getId());
        metadata.put("teamMemberId", String.valueOf(spent.getTeamMemberId()));
        auditService.recordFailure(AuditEventType.SALES_AGENT_INVITE_REPLAYED, null,
                AuditService.ACTOR_TYPE_ANONYMOUS,
                spent.getAccountUserUuid() == null ? null : String.valueOf(spent.getAccountUserUuid()),
                spent.getAccountUserUuid() == null ? null : AuditService.TARGET_TYPE_USER,
                state, metadata, context);
    }

    /**
     * Stripped and lower-cased ({@link Locale#ROOT}), or null when it is not an
     * address: the registration pattern, at most 254 characters, no control
     * characters.
     */
    static String normaliseEmail(String raw) {
        if (raw == null) return null;
        String email = raw.strip();
        if (email.isEmpty() || email.length() > MAX_EMAIL) return null;
        if (email.chars().anyMatch(Character::isISOControl)) return null;
        if (!EMAIL.matcher(email).matches()) return null;
        return email.toLowerCase(Locale.ROOT);
    }

    /** A canonical UUID (36 characters, any case), else null. */
    static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.length() != 36) return null;
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value) ? parsed : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
