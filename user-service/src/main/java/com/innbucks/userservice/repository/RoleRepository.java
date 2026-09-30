package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface RoleRepository extends JpaRepository<Role, String> {

    List<Role> findAllByOrderByBuiltinDescNameAsc();

    /**
     * The roles behind a set of names, for expanding a user's roles into
     * permissions in ONE query at login.
     *
     * <p>Names that do not resolve are simply absent from the result rather than
     * an error. That is deliberate: a user row can carry a role name whose role
     * has since been deleted, and a stale name must degrade to "grants nothing"
     * rather than failing the login outright. {@code PermissionResolver} logs the
     * gap so it is visible without being fatal.
     */
    @Query("SELECT r FROM Role r WHERE r.name IN :names")
    List<Role> findAllByNameIn(@Param("names") Collection<String> names);

    /**
     * How many users still hold this role. Guards deletion: removing a role that
     * accounts still reference would leave those rows pointing at nothing and
     * silently strip whatever it granted.
     *
     * <p>Native, because {@code user_roles} is an {@code @ElementCollection} on
     * {@link com.innbucks.userservice.entity.User} with no entity of its own to
     * write JPQL against.
     */
    @Query(value = "SELECT COUNT(*) FROM user_roles WHERE role = :name", nativeQuery = true)
    long countUsersHolding(@Param("name") String name);

    /**
     * {@code SELECT ... FOR UPDATE} on the named role rows, IN NAME ORDER (V44).
     *
     * <p>Every writer that adds a role to an account, and every role create or
     * permission edit, takes this first — before it reads any role. Without it
     * {@code setRoles} adding R while R is tenant-only could commit alongside a
     * {@code setPermissions} that makes R a staff role after its holder check
     * ran, and the account would hold platform authority nobody checked it for.
     * Name order is what keeps two writers locking overlapping sets from
     * deadlocking. Names with no row lock nothing. {@code RoleGrantRaceIT} pins it.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Role r WHERE r.name IN :names ORDER BY r.name")
    List<Role> lockAllByNameIn(@Param("names") Collection<String> names);

    /** Account ids holding a role name — {@code user_roles} has no FK to {@code roles}, so orphans count. */
    @Query(value = "SELECT DISTINCT user_id FROM user_roles WHERE role = :name", nativeQuery = true)
    List<Long> findHolderIds(@Param("name") String name);
}
