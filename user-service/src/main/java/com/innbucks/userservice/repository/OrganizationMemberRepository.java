package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.OrganizationMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    List<OrganizationMember> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    Optional<OrganizationMember> findByOrganizationIdAndUserId(UUID organizationId, Long userId);

    List<OrganizationMember> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    List<OrganizationMember> findByOrganizationIdAndRoleIn(UUID organizationId,
                                                          Collection<OrganizationMember.Role> roles);

    long countByOrganizationIdAndRole(UUID organizationId, OrganizationMember.Role role);
}
