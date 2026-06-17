package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.TeamMemberEventAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TeamMemberEventAssignmentRepository
        extends JpaRepository<TeamMemberEventAssignment, UUID> {

    List<TeamMemberEventAssignment> findByTeamMemberUserUuid(UUID teamMemberUserUuid);

    boolean existsByTeamMemberUserUuid(UUID teamMemberUserUuid);

    boolean existsByTeamMemberUserUuidAndEventId(UUID teamMemberUserUuid, UUID eventId);

    long deleteByTeamMemberUserUuidAndEventId(UUID teamMemberUserUuid, UUID eventId);
}
