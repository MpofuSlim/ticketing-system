package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * A staff account's audit history ({@code GET /admin/staff/{id}/audit}, V44),
     * newest first. Rides the existing
     * {@code idx_audit_events_target_id (target_id, occurred_at DESC)} (V15) — no
     * new index, so no blocking {@code CREATE INDEX} on the audit table during a
     * rolling deploy. USER-target rows carry the numeric {@code users.id}.
     */
    @org.springframework.data.jpa.repository.Query("SELECT e FROM AuditEvent e WHERE e.targetId = :targetId "
            + "AND e.targetType = :targetType AND e.eventType IN :types ORDER BY e.occurredAt DESC, e.id DESC")
    org.springframework.data.domain.Page<AuditEvent> findForTarget(
            @org.springframework.data.repository.query.Param("targetId") String targetId,
            @org.springframework.data.repository.query.Param("targetType") String targetType,
            @org.springframework.data.repository.query.Param("types") java.util.Collection<String> types,
            org.springframework.data.domain.Pageable pageable);
}
