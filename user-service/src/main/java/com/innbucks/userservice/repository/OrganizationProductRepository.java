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

    /** The product row, locked, so a suspend and a reinstate (or an approval) queue. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "SELECT p FROM OrganizationProduct p WHERE p.organizationId = :org AND p.product = :product")
    Optional<OrganizationProduct> lockByOrganizationIdAndProduct(
            @org.springframework.data.repository.query.Param("org") UUID organizationId,
            @org.springframework.data.repository.query.Param("product") String product);

    /**
     * Whether a business this account OWNS has {@code product} suspended (V53):
     * the mint withholds the product's role while it does. OWNER only: the role
     * came with the account's own business, and being an ADMIN of somebody
     * else's suspended business must not switch off the account's own.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(p) > 0 FROM OrganizationProduct p, OrganizationMember m
            WHERE m.organizationId = p.organizationId
              AND m.userId = :userId
              AND m.role = com.innbucks.userservice.entity.OrganizationMember.Role.OWNER
              AND p.product = :product
              AND p.status = com.innbucks.userservice.entity.OrganizationProduct.Status.SUSPENDED
            """)
    boolean ownsBusinessWithProductSuspended(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("product") String product);
}
