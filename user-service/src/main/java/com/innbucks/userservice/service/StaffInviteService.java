package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.StaffDTOs;
import com.innbucks.userservice.entity.StaffInvite;
import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.repository.MfaBackupCodeRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.StaffInviteRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.StaffInviteTokens;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The public half of staff invites (V44): {@code POST /auth/staff-invite/inspect}
 * and {@code /accept}. Unauthenticated by design — the token IS the credential.
 *
 * <p><b>Every failure is one opaque {@code 400 invite_invalid}</b>: an unknown,
 * used, revoked or expired token, and an account that is no longer
 * accept-eligible, all read the same. 400 rather than 401 so the console's 401
 * interceptor does not mistake it for an expired session, refresh, redirect and
 * swallow the message. A password mismatch is the one distinct answer, and it
 * is checked BEFORE the token is consumed so a typo does not burn the link.
 *
 * <p><b>Accept is one transaction</b>: consume the token (one conditional UPDATE
 * — of two concurrent accepts exactly one wins); check the account is still
 * accept-eligible (if not, the whole transaction rolls back so the token is NOT
 * consumed, and a {@code STAFF_GRANT_REFUSED} row with reason
 * {@code accept_ineligible} is written in its own transaction); set the
 * password; prove the email; strip the old sign-in material — the sign-in phone
 * moves to the contact number and 2FA, backup codes and remembered devices are
 * cleared, which is what locks out whoever held a legacy account before it was
 * adopted; end every session; and record {@code STAFF_INVITE_ACCEPTED},
 * REQUIRED and last. It issues no session: the person signs in next, and
 * enrols a second factor there.
 *
 * <p>A token that matches a used, revoked or expired invite is a replay:
 * {@code STAFF_INVITE_REPLAYED} (a FAILURE row naming the account) and
 * {@code user.staff.invite.replayed}. Never log the token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffInviteService {

    public static final String REPLAY_METRIC = "user.staff.invite.replayed";
    public static final String ACCEPTED_MESSAGE =
            "Your password is set. Sign in to finish setting up two-step verification.";

    private final StaffInviteRepository invites;
    private final StaffProfileRepository profiles;
    private final UserRepository users;
    private final StaffEligibility eligibility;
    private final RoleGrantGuard guard;
    private final PasswordEncoder passwordEncoder;
    private final TokenVersionBumper tokenVersionBumper;
    private final RefreshTokenRepository refreshTokens;
    private final MfaBackupCodeRepository backupCodes;
    private final DeviceTrustService deviceTrust;
    private final AuditService auditService;

    private Counter replayed;

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry registry) {
        this.replayed = registry == null ? null
                : Counter.builder(REPLAY_METRIC)
                        .description("Used, revoked or expired staff invite tokens presented again")
                        .register(registry);
    }

    /** Read-only: who the invite is for, and until when. Does not consume it. */
    @Transactional(readOnly = true)
    public StaffDTOs.InviteInspection inspect(String rawToken) {
        if (!StaffInviteTokens.wellFormed(rawToken)) throw StaffPolicyException.inviteInvalid();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        StaffInvite invite = invites.findByTokenHash(StaffInviteTokens.hash(rawToken))
                .filter(i -> i.isLive(now))
                .orElseThrow(StaffPolicyException::inviteInvalid);
        User user = users.findById(invite.getUserId()).orElseThrow(StaffPolicyException::inviteInvalid);
        if (eligibility.acceptIneligibility(user, invite.getSentToEmail()).isPresent()) {
            throw StaffPolicyException.inviteInvalid();
        }
        return new StaffDTOs.InviteInspection(user.getFirstName(), user.getEmail(), invite.getExpiresAt());
    }

    @Transactional
    public StaffDTOs.InviteAccepted accept(StaffDTOs.AcceptRequest request, AuditContext context) {
        // Before the token is consumed, so a typo does not burn the link.
        if (request.newPassword() == null || !request.newPassword().equals(request.confirmPassword())) {
            throw new AuthService.PasswordChangeException("Passwords do not match");
        }
        String rawToken = request.token();
        if (!StaffInviteTokens.wellFormed(rawToken)) throw StaffPolicyException.inviteInvalid();
        String hash = StaffInviteTokens.hash(rawToken);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Consume FIRST — nothing is read before this, so the persistence context
        // it clears holds nothing yet.
        if (invites.consume(hash, now) != 1) {
            invites.findByTokenHash(hash).ifPresent(spent -> recordReplay(spent, now, context));
            throw StaffPolicyException.inviteInvalid();
        }
        StaffInvite invite = invites.findByTokenHash(hash).orElseThrow(StaffPolicyException::inviteInvalid);
        User user = users.findById(invite.getUserId()).orElseThrow(StaffPolicyException::inviteInvalid);

        Optional<String> ineligible = eligibility.acceptIneligibility(user, invite.getSentToEmail());
        if (ineligible.isPresent()) {
            // Written in its own transaction, so it survives the rollback that
            // leaves the token unconsumed.
            guard.recordRefusal(user.getEmail(), user, "accept_ineligible",
                    Map.of("detail", ineligible.get(), "inviteId", invite.getId()));
            log.warn("Staff invite refused — account no longer accept-eligible userId={} detail={}",
                    user.getId(), ineligible.get());
            throw StaffPolicyException.inviteInvalid();
        }
        StaffProfile profile = profiles.findById(user.getId()).orElseThrow(StaffPolicyException::inviteInvalid);

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.setEmailVerifiedAt(now);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setMfaFailedAttempts(0);
        user.setMfaLockedUntil(null);
        profile.setInviteAcceptedAt(now);

        // Strip the old sign-in material (adoption and reactivation leave some):
        // a staff account has no sign-in phone — it becomes the contact number —
        // and starts its second factor from scratch.
        boolean phoneStripped = false;
        if (user.getPhoneNumber() != null) {
            if (profile.getContactPhone() == null || profile.getContactPhone().isBlank()) {
                profile.setContactPhone(user.getPhoneNumber());
            }
            user.setPhoneNumber(null);
            phoneStripped = true;
        }
        boolean mfaCleared = user.isMfaEnabled() || user.getMfaSecret() != null;
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        backupCodes.deleteAllForUser(user.getId());
        deviceTrust.clearTrustForUser(user.getId());
        users.save(user);
        profiles.save(profile);
        invites.revokeLive(user.getId(), StaffInvite.REVOKED_SUPERSEDED, now);

        // Every session the account held ends; published after commit.
        long version = tokenVersionBumper.bump(user);
        int refreshRevoked = refreshTokens.revokeAllForUser(user.getId(), Instant.now());
        users.flush();
        log.info("Staff invite accepted userId={} adopted={} phoneStripped={} mfaCleared={}",
                user.getId(), profile.isAdopted(), phoneStripped, mfaCleared);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetEmail", user.getEmail());
        metadata.put("adopted", profile.isAdopted());
        metadata.put("inviteId", invite.getId());
        metadata.put("phoneStripped", phoneStripped);
        metadata.put("mfaCleared", mfaCleared);
        metadata.put("tokenVersion", version);
        metadata.put("refreshTokensRevoked", refreshRevoked);
        auditService.recordRequired(AuditEventType.STAFF_INVITE_ACCEPTED, user.getEmail(),
                AuditService.ACTOR_TYPE_USER, String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                metadata, context);
        return new StaffDTOs.InviteAccepted(user.getEmail());
    }

    /** A used, revoked or expired invite presented again: a FAILURE row naming the account, and the metric. */
    private void recordReplay(StaffInvite spent, LocalDateTime now, AuditContext context) {
        String state = spent.getUsedAt() != null ? "used"
                : spent.getRevokedAt() != null ? "revoked:" + spent.getRevokedReason()
                : !spent.getExpiresAt().isAfter(now) ? "expired" : "unknown";
        log.warn("Staff invite replayed inviteId={} userId={} state={}", spent.getId(), spent.getUserId(), state);
        if (replayed != null) replayed.increment();
        auditService.recordFailure(AuditEventType.STAFF_INVITE_REPLAYED, null, AuditService.ACTOR_TYPE_ANONYMOUS,
                String.valueOf(spent.getUserId()), AuditService.TARGET_TYPE_USER, state,
                Map.of("inviteId", spent.getId()), context);
    }
}
