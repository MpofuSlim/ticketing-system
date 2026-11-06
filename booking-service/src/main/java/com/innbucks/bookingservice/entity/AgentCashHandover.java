package com.innbucks.bookingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Cash an organizer received from one of their sales agents (V36). Recorded
 * by the organizer, never by the agent; read by both sales-agent reports,
 * where it reduces what the agent still owes. Never deleted: a mistaken entry
 * is soft-deleted ({@link #deletedAt}, by whom), and the reports skip it.
 * Everything but the soft-delete fields is written once, at insert.
 */
@Entity
@Table(name = "agent_cash_handovers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentCashHandover {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The organizer whose agent handed the cash over (the bookings' tenant_user_uuid). */
    @Column(name = "organizer_uuid", nullable = false, updatable = false)
    private UUID organizerUuid;

    /** The agent's event-team row (event-service scanner_id), as on the bookings it sold. */
    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    /** The event the cash was for; null = no event in particular. */
    @Column(name = "event_id", updatable = false)
    private UUID eventId;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    /** When the cash changed hands, UTC. */
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "recorded_by_user_uuid", updatable = false)
    private UUID recordedByUserUuid;

    @Column(name = "recorded_by", length = 254, updatable = false)
    private String recordedBy;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by_user_uuid")
    private UUID deletedByUserUuid;

    @Column(name = "deleted_by", length = 254)
    private String deletedBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
