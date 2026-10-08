package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.TenantProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TenantProfileRepository extends JpaRepository<TenantProfile, Long> {
    Optional<TenantProfile> findByUserId(Long userId);

    /**
     * Duplicate-BPO guard for business registration. The BPO / tax number
     * must be unique across registered businesses; register checks this before
     * creating the profile so a second business can't claim the same number.
     */
    boolean existsByBpoNumber(String bpoNumber);

    // Batch lookup so listings can attach business details without an N+1
    // query per user.
    List<TenantProfile> findByUserIdIn(Collection<Long> userIds);

    /**
     * The business name alone, for a rejected registration's notice and audit
     * row — a projection, so the profile entity is never loaded into the session
     * that is about to delete it.
     */
    @Query("SELECT p.businessName FROM TenantProfile p WHERE p.user.id = :userId")
    Optional<String> findBusinessNameByUserId(@Param("userId") Long userId);

    /**
     * Removes the account's tenant profile — a rejected registration's business
     * details, which frees its BPO / tax number for a new registration.
     * {@code tenant_profiles.user_id} has no cascade (V1), so this runs before
     * the account is deleted.
     */
    @Modifying
    @Query("DELETE FROM TenantProfile p WHERE p.user.id = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
