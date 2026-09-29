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
     * A bulk sign-out ({@link #bumpAllHolding}) above this many accounts is
     * logged at WARN and counted on {@link #LARGE_BULK_BUMP_METRIC}. The bulk
     * bump is sized by the ROLE's holders, not by any staff population: a
     * PLATFORM (or stale) code removed from a business role such as
     * {@code MERCHANT_ADMIN} signs every business out at once. That is correct —
     * the code was on their tokens — but it should never be a surprise.
     */
    public static final int LARGE_BULK_BUMP_THRESHOLD = 500;

    /** Bulk sign-outs above {@link #LARGE_BULK_BUMP_THRESHOLD} holders. */
    public static final String LARGE_BULK_BUMP_METRIC = "user.tokenver.bulk_bump.large";

    /** Null without a registry (plain unit tests): the WARN still logs, nothing is counted. */
    private io.micrometer.core.instrument.Counter largeBulkBumps;

    /** Registers {@link #LARGE_BULK_BUMP_METRIC} at zero, so its first increase is visible. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.largeBulkBumps = meterRegistry == null ? null
                : io.micrometer.core.instrument.Counter.builder(LARGE_BULK_BUMP_METRIC)
                        .description("Role-wide token_version bumps (a PLATFORM code removed from a role) "
                                + "that signed out more than " + LARGE_BULK_BUMP_THRESHOLD + " accounts")
                        .register(meterRegistry);
    }

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
     * {@code UPDATE ... RETURNING}, and publishes the new versions to the shared
     * Redis after commit — in pipelined batches through ONE synchronization
     * ({@link TokenVersionPublisher#publishAllAfterCommit}), not one synchronous
     * round trip per holder on the request thread. For a role that just lost a
     * PLATFORM permission: its holders' access tokens carry the old permission
     * set, and every one of them must stop working at once rather than at
     * expiry. Returns how many accounts were bumped.
     *
     * <p>The cost is the role's holder count: every row is returned and held in
     * memory until commit (a uuid and a version each). Above
     * {@link #LARGE_BULK_BUMP_THRESHOLD} it is logged at WARN and counted.
     *
     * <p>Entities already loaded in this persistence context keep their old
     * in-memory value; {@code token_version} is {@code updatable = false}, so no
     * later save can write it back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int bumpAllHolding(String roleName) {
        java.util.List<Object[]> rows = userRepository.incrementTokenVersionForRoleHolders(roleName);
        java.util.Map<java.util.UUID, Long> versions = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            java.util.UUID userUuid = row[0] instanceof java.util.UUID u ? u
                    : java.util.UUID.fromString(String.valueOf(row[0]));
            versions.put(userUuid, ((Number) row[1]).longValue());
        }
        tokenVersionPublisher.publishAllAfterCommit(versions);
        if (rows.size() > LARGE_BULK_BUMP_THRESHOLD) {
            log.warn("token_version bumped for every holder of role={} count={} — a role-wide sign-out above "
                    + "{} accounts (a PLATFORM or stale code removed from a widely held role)",
                    roleName, rows.size(), LARGE_BULK_BUMP_THRESHOLD);
            if (largeBulkBumps != null) largeBulkBumps.increment();
        } else {
            log.info("token_version bumped for every holder of role={} count={}", roleName, rows.size());
        }
        return rows.size();
    }

    private long apply(User user, long next) {
        user.setTokenVersion(next);
        tokenVersionPublisher.publishAfterCommit(user.getUserUuid(), next);
        log.debug("token_version bumped userId={} newVersion={}", user.getId(), next);
        return next;
    }
}
