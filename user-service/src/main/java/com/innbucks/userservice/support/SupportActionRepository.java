package com.innbucks.userservice.support;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportActionRepository extends JpaRepository<SupportAction, Long> {

    Optional<SupportAction> findByAgentUserUuidAndIdempotencyKey(UUID agentUserUuid, UUID idempotencyKey);

    /**
     * When each successful {@code op} on {@code target} since {@code since} was
     * made, oldest first — the support reset-code quota, and its wait.
     */
    @Query("""
            SELECT a.createdAt FROM SupportAction a
             WHERE a.target = :target
               AND a.op = :op
               AND a.outcome = 'SUCCESS'
               AND a.createdAt > :since
             ORDER BY a.createdAt
            """)
    List<LocalDateTime> successTimesSince(@Param("target") String target, @Param("op") String op,
                                          @Param("since") LocalDateTime since);
}
