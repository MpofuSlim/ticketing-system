package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.TenantProfile;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
