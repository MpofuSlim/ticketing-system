package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.TenantMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantMemberRepository extends JpaRepository<TenantMember, UUID> {

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);

    List<TenantMember> findByTenantId(UUID tenantId);

    List<TenantMember> findByEmail(String email);

    Optional<TenantMember> findByTenantIdAndEmail(UUID tenantId, String email);

    void deleteByTenantIdAndEmail(UUID tenantId, String email);
}
