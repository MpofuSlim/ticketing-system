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
}
