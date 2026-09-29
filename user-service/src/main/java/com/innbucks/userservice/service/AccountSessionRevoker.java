package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OtpRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Deactivates an account and ends every session it holds, at once — the one
 * implementation behind {@code PUT /admin/users/{id}/active} with
 * {@code false} and the organizer's team-member disable.
 *
 * <p><b>What used to go wrong.</b> Deactivation flipped {@code users.active} and
 * nothing else: no {@code tokenVersion} bump, no Redis publish, no refresh
 * revocation, and {@code /auth/refresh} never looked at {@code active}. A
 * deactivated person therefore kept every open session — refreshing into new
 * ones for the life of the refresh chain — and kept whatever they had planted
 * for later (a remembered device, a live password-reset code).
 *
 * <p><b>What {@link #revokeAll} does</b>, inside the caller's transaction so
 * every step commits or rolls back with the deactivation itself:
 * <ol>
 *   <li>{@code UPDATE users SET active = false, token_version = token_version + 1
 *       ... RETURNING} — one atomic statement ({@link TokenVersionBumper}), so a
 *       login racing it cannot write the old version back; every access token
 *       and pending mfaToken dies with it;</li>
 *   <li>revokes every refresh-token family;</li>
 *   <li>clears "remember this device" trust, so a reactivation does not
 *       inherit a standing 2FA bypass;</li>
 *   <li>deletes the live reset-OTP rows keyed by the account's email and phone,
 *       so a code requested BEFORE the deactivation cannot set a password for
 *       use after a reactivation (a code cannot be requested DURING it:
 *       {@code PasswordResetService} treats a deactivated account like an
 *       unknown one);</li>
 *   <li>after commit, publishes {@code auth:tokenver:<userUuid>} so every other
 *       service rejects the old access tokens immediately (a failed publish is
 *       counted as {@code user.tokenver.publish_failed}; those services then
 *       fail open until each token expires).</li>
 * </ol>
 *
 * <p>{@link #sweepOnReactivation} runs the same sweep (minus the deactivation)
 * when an account comes back. It changes nothing for an account deactivated
 * through {@link #revokeAll} — there is nothing left to end — but accounts
 * deactivated before this class existed still hold live refresh families,
 * device trust and access tokens, which a reactivation would otherwise bring
 * straight back.
 *
 * <p>Auditing and the deactivation notice stay with the caller, which knows who
 * acted and why.
 *
 * <p><b>Not reached:</b> loyalty's phone-keyed {@code LRT-} refresh chains and
 * loyalty sessions. A customer deactivated here keeps those until they expire,
 * and {@code POST /auth/otp/verify} still mints a NEW {@code loyalty-otp}
 * session (and promotes the phone in loyalty) for that customer's phone. They
 * are roles-empty and carry no fleet authority beyond loyalty; whether a
 * deactivation here should also switch loyalty off is loyalty's decision.
 * Documented, not fixed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSessionRevoker {

    private final TokenVersionBumper tokenVersionBumper;
    private final RefreshTokenRepository refreshTokenRepository;
    private final DeviceTrustService deviceTrustService;
    private final OtpRepository otpRepository;

    /** What one revocation did — for the caller's audit row and log line. */
    public record Revocation(long tokenVersion, int refreshTokensRevoked, int resetCodesDeleted) {
    }

    /**
     * Deactivate {@code user} and end every session it holds. The entity is
     * updated in memory ({@code active = false}, the new {@code tokenVersion}).
     *
     * @param reason a short machine-readable tag for the log line
     *               (e.g. {@code admin_deactivation}, {@code team_member_disabled})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Revocation revokeAll(User user, String reason) {
        return endSessions(user, tokenVersionBumper.deactivateAndBump(user), reason);
    }

    /**
     * Reactivation (an approved account going from inactive back to active):
     * bump, revoke every refresh family, clear device trust and delete live reset
     * codes, so the account starts from a clean slate — "re-activating does not
     * restore any of it" holds whenever and however the account was deactivated.
     * Nothing legitimate is lost: every session path refuses an inactive account,
     * so none was minted while it was off.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Revocation sweepOnReactivation(User user) {
        return endSessions(user, tokenVersionBumper.bump(user), "reactivation");
    }

    private Revocation endSessions(User user, long version, String reason) {
        int refreshRevoked = refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
        deviceTrustService.clearTrustForUser(user.getId());
        int codesDeleted = deleteResetCodes(user);
        log.info("Account sessions revoked userId={} reason={} newTokenVersion={} "
                        + "refreshTokensRevoked={} resetCodesDeleted={}",
                user.getId(), reason, version, refreshRevoked, codesDeleted);
        return new Revocation(version, refreshRevoked, codesDeleted);
    }

    /**
     * Password-reset codes are keyed by the identifier the reset was started
     * with — the stored email exactly (PasswordResetService resolves the account
     * with an exact match before minting one) or the E.164 phone. Deleting both
     * keys is what stops a code requested before the deactivation from being
     * redeemed after a reactivation.
     */
    private int deleteResetCodes(User user) {
        int deleted = 0;
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            deleted += otpRepository.deleteByPhoneNumber(user.getEmail());
        }
        if (user.getPhoneNumber() != null && !user.getPhoneNumber().isBlank()) {
            deleted += otpRepository.deleteByPhoneNumber(user.getPhoneNumber());
        }
        return deleted;
    }
}
