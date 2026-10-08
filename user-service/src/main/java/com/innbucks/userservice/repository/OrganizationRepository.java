package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID>,
        JpaSpecificationExecutor<Organization> {

    /**
     * Deletes the organizations a rejected registration created. One statement;
     * their {@code organization_members} and {@code organization_products} rows
     * go with them through V39's {@code ON DELETE CASCADE}.
     */
    @Modifying
    @Query("DELETE FROM Organization o WHERE o.id IN :ids")
    int deleteAllWithIds(@Param("ids") Collection<UUID> ids);
}
