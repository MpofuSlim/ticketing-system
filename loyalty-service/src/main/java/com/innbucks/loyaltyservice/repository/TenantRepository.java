package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findByCode(String code);
}
