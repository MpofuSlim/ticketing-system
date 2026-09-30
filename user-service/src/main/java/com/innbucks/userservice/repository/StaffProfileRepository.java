package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.StaffProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, Long> {

    List<StaffProfile> findAllByUserIdIn(Collection<Long> userIds);

    /**
     * Staff accounts a caller created in the window — the per-caller create
     * quota. {@code users.created_by} is stamped by JPA auditing with the
     * caller's {@code user_uuid} (or, without one, their email), so both
     * spellings of the caller are matched.
     */
    @Query(value = "SELECT COUNT(*) FROM staff_profiles p JOIN users u ON u.id = p.user_id "
            + "WHERE p.adopted = FALSE AND p.created_at > :since AND u.created_by IN (:creators)",
            nativeQuery = true)
    long countCreatedBySince(@Param("creators") Collection<String> creators, @Param("since") LocalDateTime since);

    /** The oldest create in the window — when the quota next frees a slot. */
    @Query(value = "SELECT MIN(p.created_at) FROM staff_profiles p JOIN users u ON u.id = p.user_id "
            + "WHERE p.adopted = FALSE AND p.created_at > :since AND u.created_by IN (:creators)",
            nativeQuery = true)
    LocalDateTime oldestCreatedBySince(@Param("creators") Collection<String> creators,
                                       @Param("since") LocalDateTime since);

    // ---- the invariant gauge (read-only) ----------------------------------

    /** Profiled accounts that belong to an ACTIVE organization. */
    @Query(value = "SELECT COUNT(DISTINCT p.user_id) FROM staff_profiles p "
            + "JOIN organization_members m ON m.user_id = p.user_id "
            + "JOIN organizations o ON o.id = m.organization_id WHERE o.status = 'ACTIVE'",
            nativeQuery = true)
    long countWithActiveOrganization();

    /** Profiled accounts whose invite was accepted but which still have a sign-in phone. */
    @Query(value = "SELECT COUNT(*) FROM staff_profiles p JOIN users u ON u.id = p.user_id "
            + "WHERE p.invite_accepted_at IS NOT NULL AND u.phone_number IS NOT NULL",
            nativeQuery = true)
    long countAcceptedWithLoginPhone();
}
