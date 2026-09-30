package com.innbucks.userservice.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SupportActionRepository extends JpaRepository<SupportAction, Long> {

    Optional<SupportAction> findByAgentUserUuidAndIdempotencyKey(UUID agentUserUuid, UUID idempotencyKey);
}
