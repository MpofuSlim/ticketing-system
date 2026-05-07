package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    List<Tenant> findAllByCode(String code);
}
