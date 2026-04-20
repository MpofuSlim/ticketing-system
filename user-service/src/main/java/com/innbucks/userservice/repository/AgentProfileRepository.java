package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.AgentProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AgentProfileRepository extends JpaRepository<AgentProfile, Long> {
    Optional<AgentProfile> findByUserId(Long userId);
}
