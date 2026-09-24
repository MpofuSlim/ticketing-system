package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.OrganizationProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationProductRepository extends JpaRepository<OrganizationProduct, UUID> {

    List<OrganizationProduct> findByOrganizationId(UUID organizationId);

    List<OrganizationProduct> findByOrganizationIdIn(Collection<UUID> organizationIds);

    Optional<OrganizationProduct> findByOrganizationIdAndProduct(UUID organizationId, String product);
}
