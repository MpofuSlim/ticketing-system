package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    List<User> findByUserUuidIn(Collection<UUID> userUuids);
    Optional<User> findByPhoneNumber(String phoneNumber);
    boolean existsByEmail(String email);
    /**
     * Case-insensitive email existence check. Registration uses this so a
     * case-variant of an already-registered address ({@code John@Example.com}
     * vs {@code john@example.com}) is rejected as a duplicate — the
     * {@code uk_users_email} constraint (V1) is case-sensitive, so the exact
     * {@link #existsByEmail(String)} alone would let the variant through.
     */
    boolean existsByEmailIgnoreCase(String email);

    /**
     * Every account whose email matches case-insensitively. A LIST, because
     * {@code uk_users_email} is case-sensitive and accounts created before the
     * case-insensitive registration check may genuinely share a spelling
     * variant — a single-result finder would throw on exactly those rows.
     */
    List<User> findAllByEmailIgnoreCase(String email);
    boolean existsByPhoneNumber(String phoneNumber);
    /**
     * Composite uniqueness check matching the {@code uk_users_phone_country}
     * constraint (V18). Use this from new-user paths so the existence check
     * and the DB constraint agree on the identity tuple; the older
     * {@link #existsByPhoneNumber(String)} stays for paths that legitimately
     * want "is this phone in use anywhere in this cell" (in practice that's
     * the same thing today, since one cell == one country, but the right
     * tuple keeps reading honest).
     */
    boolean existsByPhoneNumberAndHomeCountry(String phoneNumber, String homeCountry);
    List<User> findByActive(boolean active);
    List<User> findByLoyaltyShopId(UUID loyaltyShopId);
    List<User> findByLoyaltyMerchantId(UUID loyaltyMerchantId);

    Optional<User> findByUserUuid(UUID userUuid);

    /**
     * Every team member created by a given organizer. Used by the
     * organizer's team-management endpoints
     * ({@code GET /event-organizer/team-members}); the FK column is
     * populated only on rows whose role is TEAM_MEMBER, so this never
     * returns non-team-member users.
     */
    List<User> findByCreatedByOrganizerUuid(UUID organizerUuid);

    /**
     * "System users" projection — every row except those whose roles
     * include the supplied value. Used by the SUPER_ADMIN portal to list
     * administrators / staff while keeping the (much larger) customer
     * population off the page. `NOT MEMBER OF` is the JPA-standard way to
     * filter an @ElementCollection without dropping to a native query.
     *
     * <p>Takes a role NAME rather than the {@code User.Role} enum as of V35:
     * {@code u.roles} is a {@code Set<String>} now, so an enum parameter would
     * compile and then fail to match anything at runtime.
     */
    @Query("SELECT u FROM User u WHERE :role NOT MEMBER OF u.roles")
    List<User> findAllExcludingRole(@Param("role") String role);

    @Query("SELECT u FROM User u WHERE u.active = :active AND :role NOT MEMBER OF u.roles")
    List<User> findByActiveExcludingRole(@Param("active") boolean active, @Param("role") String role);

    /**
     * Users carrying ANY of the supplied roles. Backs the SUPER_ADMIN
     * {@code GET /admin/users/merchants} listing (MERCHANT_ADMIN +
     * EVENT_ORGANIZER) and is generic enough to extend to other
     * role-scoped admin views. Joins the {@code roles}
     * {@link jakarta.persistence.ElementCollection} and matches with
     * {@code IN} — the JPA-standard way to test membership without
     * dropping to native SQL. {@code DISTINCT} collapses the duplicate
     * rows a user with more than one of the requested roles would
     * otherwise produce.
     */
    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r IN :roles")
    List<User> findByAnyRole(@Param("roles") Collection<String> roles);

    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE u.active = :active AND r IN :roles")
    List<User> findByActiveAndAnyRole(@Param("active") boolean active, @Param("roles") Collection<String> roles);

    /**
     * Project-only lookup of {@code (token_version, active)} for a token
     * subject. JwtFilter calls this on every authenticated request to validate
     * the JWT's session epoch against the current DB value AND to refuse a
     * deactivated account at once — not merely once its access token expires.
     * Selecting the two columns directly avoids loading the whole {@link User}
     * entity (with its eager {@code roles} and {@code defaultServices}
     * collections) just to read them. Empty when the subject doesn't resolve,
     * which JwtFilter treats the same as a stale token.
     */
    @Query("SELECT new com.innbucks.userservice.repository.UserTokenState(u.tokenVersion, u.active) "
            + "FROM User u WHERE u.email = :subject OR u.phoneNumber = :subject")
    Optional<UserTokenState> findTokenStateBySubject(@Param("subject") String subject);

    /** The same projection by primary key — the mfaToken's subject is the user id. */
    @Query("SELECT new com.innbucks.userservice.repository.UserTokenState(u.tokenVersion, u.active) "
            + "FROM User u WHERE u.id = :id")
    Optional<UserTokenState> findTokenStateById(@Param("id") Long id);

    // ------------------------------------------------------------------
    // token_version writers. The ONLY statements in the codebase that move
    // users.token_version (the entity column is updatable = false). Each is a
    // single atomic `UPDATE ... RETURNING`, so two concurrent writers can never
    // both read v and both write v+1: Postgres serialises them on the row lock
    // and the second re-evaluates its WHERE against the first one's committed
    // row. Call them through TokenVersionBumper, never directly — it keeps the
    // in-memory entity in step and publishes the new value after commit.
    //
    // Not @Modifying: a native UPDATE ... RETURNING yields a result set, so it
    // is executed as a single-result query. Each returns null when no row
    // matched (unknown id, or the guard in the WHERE clause failed).
    // ------------------------------------------------------------------

    /** Unconditional bump. */
    @Query(value = "UPDATE users SET token_version = token_version + 1 WHERE id = :id "
            + "RETURNING token_version", nativeQuery = true)
    Long incrementTokenVersion(@Param("id") Long id);

    /**
     * Bump only while the account is still active. The password step of a
     * login uses this, so a login that races a deactivation either commits its
     * bump FIRST (and the deactivation then bumps past it) or finds the
     * committed {@code active = false} and matches nothing.
     */
    @Query(value = "UPDATE users SET token_version = token_version + 1 WHERE id = :id AND active = TRUE "
            + "RETURNING token_version", nativeQuery = true)
    Long incrementTokenVersionIfActive(@Param("id") Long id);

    /**
     * Compare-and-set bump: only when the version is still {@code expected} and
     * the account still active. This is what SPENDS an mfaToken — of two
     * concurrent verifies presenting the same token, exactly one matches.
     */
    @Query(value = "UPDATE users SET token_version = token_version + 1 "
            + "WHERE id = :id AND token_version = :expected AND active = TRUE "
            + "RETURNING token_version", nativeQuery = true)
    Long incrementTokenVersionIfCurrent(@Param("id") Long id, @Param("expected") long expected);

    /**
     * Bump EVERY holder of a role in one statement — a role losing a PLATFORM
     * permission ({@code RoleAdminService.setPermissions}). One {@code UPDATE}
     * rather than a loop of per-user bumps, so the whole population moves
     * atomically with the permission edit. Returns one {@code [user_uuid,
     * token_version]} row per account bumped (possibly none).
     *
     * <p>{@code user_roles} has no foreign key to {@code roles}, so this matches
     * on the string: an account holding the name is a holder whether or not the
     * row predates the role.
     */
    @Query(value = "UPDATE users SET token_version = token_version + 1 "
            + "WHERE id IN (SELECT ur.user_id FROM user_roles ur WHERE ur.role = :role) "
            + "RETURNING user_uuid, token_version", nativeQuery = true)
    List<Object[]> incrementTokenVersionForRoleHolders(@Param("role") String role);

    /** Deactivate and bump in one statement — AccountSessionRevoker's first step. */
    @Query(value = "UPDATE users SET active = FALSE, token_version = token_version + 1 WHERE id = :id "
            + "RETURNING token_version", nativeQuery = true)
    Long deactivateAndIncrementTokenVersion(@Param("id") Long id);

    /**
     * Stamp {@code credential_delivered_at} and nothing else.
     *
     * <p>Deliberately a targeted UPDATE rather than a
     * {@code findById} → setter → {@code save} round-trip. That round-trip is a
     * read-modify-write: before {@link User} gained {@code @DynamicUpdate} the
     * {@code save} emitted a full-column UPDATE built from whatever the entity
     * was loaded with — {@code password}, {@code roles}, {@code active} and all —
     * and even now a stale snapshot of any column the round-trip touches would
     * be written back.
     * Its only caller is {@code CredentialDeliveryListener}, which is
     * {@code @Async} + {@code AFTER_COMMIT}: it reads on a background thread
     * some time after the activation transaction committed, so any write to
     * that user in between (a password change, an admin edit) is silently
     * reverted when the stale snapshot lands. One column in, one column out,
     * and there is nothing to lose.
     *
     * @return rows updated — 0 means the user vanished between publish and callback
     */
    @Modifying
    @Query("UPDATE User u SET u.credentialDeliveredAt = :at WHERE u.id = :id")
    int markCredentialDelivered(@Param("id") Long id, @Param("at") LocalDateTime at);
}
