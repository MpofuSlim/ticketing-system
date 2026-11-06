package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.AgentCashHandover;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cash handed over by sales agents (V36). Every read is scoped: by the
 * organizer (null = platform staff, every organizer) or by the agent ids an
 * account has sold under. Soft-deleted rows are left out of every total.
 */
public interface AgentCashHandoverRepository extends JpaRepository<AgentCashHandover, UUID> {

    /** Live handovers received in [start, end), for the organizer's sales-agent report. */
    @Query("""
        SELECT h FROM AgentCashHandover h
        WHERE h.deletedAt IS NULL
          AND (:organizerUuid IS NULL OR h.organizerUuid = :organizerUuid)
          AND (:eventId IS NULL OR h.eventId = :eventId)
          AND h.receivedAt >= :start AND h.receivedAt < :end
        ORDER BY h.receivedAt ASC, h.id ASC
    """)
    List<AgentCashHandover> findLive(@Param("organizerUuid") @Nullable UUID organizerUuid,
                                     @Param("eventId") @Nullable UUID eventId,
                                     @Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);

    /** Live handovers of these agent rows received in [start, end), for the agent's own report. */
    @Query("""
        SELECT h FROM AgentCashHandover h
        WHERE h.deletedAt IS NULL
          AND h.agentId IN :agentIds
          AND (:eventId IS NULL OR h.eventId = :eventId)
          AND h.receivedAt >= :start AND h.receivedAt < :end
        ORDER BY h.receivedAt ASC, h.id ASC
    """)
    List<AgentCashHandover> findLiveForAgents(@Param("agentIds") Collection<UUID> agentIds,
                                              @Param("eventId") @Nullable UUID eventId,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    /** Every handover of one agent, soft-deleted ones included, newest first: the organizer's list. */
    @Query("""
        SELECT h FROM AgentCashHandover h
        WHERE h.agentId = :agentId
          AND (:organizerUuid IS NULL OR h.organizerUuid = :organizerUuid)
        ORDER BY h.receivedAt DESC, h.id DESC
    """)
    List<AgentCashHandover> findAllForAgent(@Param("organizerUuid") @Nullable UUID organizerUuid,
                                            @Param("agentId") UUID agentId);

    Optional<AgentCashHandover> findByIdAndOrganizerUuidAndAgentId(UUID id, UUID organizerUuid, UUID agentId);

    /**
     * Soft-deletes a live handover of this organizer and agent in one statement;
     * 0 when it is already deleted or is not theirs. The first deletion is the
     * one recorded.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE AgentCashHandover h
           SET h.deletedAt = :now, h.deletedByUserUuid = :byUserUuid, h.deletedBy = :byName
         WHERE h.id = :id AND h.organizerUuid = :organizerUuid AND h.agentId = :agentId
           AND h.deletedAt IS NULL
    """)
    int softDelete(@Param("id") UUID id,
                   @Param("organizerUuid") UUID organizerUuid,
                   @Param("agentId") UUID agentId,
                   @Param("now") LocalDateTime now,
                   @Param("byUserUuid") @Nullable UUID byUserUuid,
                   @Param("byName") @Nullable String byName);
}
