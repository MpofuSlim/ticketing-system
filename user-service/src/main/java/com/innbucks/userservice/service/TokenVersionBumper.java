package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.TokenVersionPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one writer of {@code users.token_version} — the session epoch every access
 * token and mfaToken carries, and the lever that ends sessions.
 *
 * <p><b>Why a helper and not {@code user.setTokenVersion(v + 1)}.</b> The old
 * read-modify-write bumped the value the entity happened to be loaded with. Two
 * writers that loaded the same row both wrote v+1, and a stale save (User then
 * had no {@code @DynamicUpdate}, so every save rewrote every column) could put
 * an old version straight back — a login racing a deactivation revived the
 * sessions the deactivation had just ended. Every method here is a single
 * {@code UPDATE ... SET token_version = token_version + 1 ... RETURNING}: the
 * database does the arithmetic under the row lock, so concurrent bumps never
 * collapse and the entity column ({@code updatable = false}) can no longer be
 * written back by anyone.
 *
 * <p>Each method then (1) copies the returned value onto the in-memory entity,
 * so a token minted later in the same transaction carries it, and (2) publishes
 * it to the shared Redis <em>after commit</em>
 * ({@link TokenVersionPublisher#publishAfterCommit}), so downstream services
 * never see a version Postgres rolled back.
 *
 * <p>{@code MANDATORY}: a bump is always part of a larger change (a
 * deactivation, a role edit, a password change) and must commit or roll back
 * with it. Calling one outside a transaction is a bug, so it fails loudly.
 *
 * <p>{@code TokenVersionBumpSitesTest} fails the build on a
 * {@code setTokenVersion} anywhere in {@code src/main} but this class.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenVersionBumper {

    private final UserRepository userRepository;
    private final TokenVersionPublisher tokenVersionPublisher;

    /**
     * Unconditional bump: ends every access token and pending mfaToken the
     * account holds. Returns the new version.
     *
     * @throws IllegalStateException when the row no longer exists
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long bump(User user) {
        Long next = userRepository.incrementTokenVersion(user.getId());
        if (next == null) {
            throw new IllegalStateException("User vanished during a token-version bump: " + user.getId());
        }
        return apply(user, next);
    }

    /**
     * Bump only while the account is active — the password step of a login.
     * Returns false (and changes nothing) when a concurrent deactivation has
     * already committed; the caller must then refuse the sign-in.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean bumpIfActive(User user) {
        Long next = userRepository.incrementTokenVersionIfActive(user.getId());
        if (next == null) {
            return false;
        }
        apply(user, next);
        return true;
    }

    /**
     * Compare-and-set bump: succeeds only while the version is still
     * {@code expected} and the account active. This SPENDS an mfaToken bound to
     * {@code expected}: of two concurrent verifies presenting the same token,
     * exactly one gets {@code true}; any bump in between (a deactivation, a role
     * change, a password reset, an admin MFA reset, a newer login) makes it
     * {@code false}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean bumpIfCurrent(User user, long expected) {
        Long next = userRepository.incrementTokenVersionIfCurrent(user.getId(), expected);
        if (next == null) {
            return false;
        }
        apply(user, next);
        return true;
    }

    /**
     * Sets {@code active = false} and bumps, in ONE statement — the first step
     * of {@link AccountSessionRevoker#revokeAll}. One statement rather than two
     * so there is no instant at which the account is inactive with its old
     * tokens still current, or current with the account still active.
     *
     * @throws IllegalStateException when the row no longer exists
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long deactivateAndBump(User user) {
        Long next = userRepository.deactivateAndIncrementTokenVersion(user.getId());
        if (next == null) {
            throw new IllegalStateException("User vanished during deactivation: " + user.getId());
        }
        user.setActive(false);
        return apply(user, next);
    }

    /**
     * Bumps every account holding {@code roleName}, in ONE atomic
     * {@code UPDATE ... RETURNING}, and publishes each new version to the shared
     * Redis after commit. For a role that just lost a PLATFORM permission: its
     * holders' access tokens carry the old permission set, and every one of them
     * must stop working at once rather than at expiry. Returns how many accounts
     * were bumped.
     *
     * <p>Entities already loaded in this persistence context keep their old
     * in-memory value; {@code token_version} is {@code updatable = false}, so no
     * later save can write it back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int bumpAllHolding(String roleName) {
        java.util.List<Object[]> rows = userRepository.incrementTokenVersionForRoleHolders(roleName);
        for (Object[] row : rows) {
            java.util.UUID userUuid = row[0] instanceof java.util.UUID u ? u
                    : java.util.UUID.fromString(String.valueOf(row[0]));
            long version = ((Number) row[1]).longValue();
            tokenVersionPublisher.publishAfterCommit(userUuid, version);
        }
        log.info("token_version bumped for every holder of role={} count={}", roleName, rows.size());
        return rows.size();
    }

    private long apply(User user, long next) {
        user.setTokenVersion(next);
        tokenVersionPublisher.publishAfterCommit(user.getUserUuid(), next);
        log.debug("token_version bumped userId={} newVersion={}", user.getId(), next);
        return next;
    }
}
