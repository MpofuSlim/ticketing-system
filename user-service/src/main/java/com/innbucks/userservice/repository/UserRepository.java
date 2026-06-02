package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhoneNumber(String phoneNumber);
    boolean existsByEmail(String email);
    boolean existsByPhoneNumber(String phoneNumber);
    List<User> findByActive(boolean active);
    List<User> findByLoyaltyShopId(UUID loyaltyShopId);
    List<User> findByLoyaltyMerchantId(UUID loyaltyMerchantId);

    /**
     * "System users" projection — every row except those whose roles
     * include the supplied value. Used by the SUPER_ADMIN portal to list
     * administrators / staff while keeping the (much larger) customer
     * population off the page. `NOT MEMBER OF` is the JPA-standard way to
     * filter an @ElementCollection without dropping to a native query.
     */
    @Query("SELECT u FROM User u WHERE :role NOT MEMBER OF u.roles")
    List<User> findAllExcludingRole(@Param("role") User.Role role);

    @Query("SELECT u FROM User u WHERE u.active = :active AND :role NOT MEMBER OF u.roles")
    List<User> findByActiveExcludingRole(@Param("active") boolean active, @Param("role") User.Role role);

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
    List<User> findByAnyRole(@Param("roles") Collection<User.Role> roles);

    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE u.active = :active AND r IN :roles")
    List<User> findByActiveAndAnyRole(@Param("active") boolean active, @Param("roles") Collection<User.Role> roles);

    /**
     * Project-only lookup for the token_version column. JwtFilter calls this
     * on every authenticated request to validate the JWT's session epoch
     * against the current DB value — fetching the column directly avoids
     * loading the whole {@link User} entity (with its eager
     * {@code roles} and {@code defaultServices} collections) just to read
     * one number. Returns empty when the subject doesn't resolve to a user,
     * which JwtFilter treats the same as a stale token.
     */
    @Query("SELECT u.tokenVersion FROM User u WHERE u.email = :subject OR u.phoneNumber = :subject")
    Optional<Long> findTokenVersionBySubject(@Param("subject") String subject);
}
