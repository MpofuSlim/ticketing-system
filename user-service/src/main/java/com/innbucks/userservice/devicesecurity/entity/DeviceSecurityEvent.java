package com.innbucks.userservice.devicesecurity.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the device-security decision log (contract §3 rule 9). Append-only.
 * See V40 for why this is not the HMAC-chained audit_events table.
 */
@Entity
@Table(name = "device_security_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceSecurityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "event_type", nullable = false, length = 40, updatable = false)
    private String eventType;

    @Column(name = "msisdn", length = 20, updatable = false)
    private String msisdn;

    @Column(name = "device_id", updatable = false)
    private UUID deviceId;

    @Column(name = "install_id_hash", length = 64, updatable = false)
    private String installIdHash;

    @Column(name = "decision", length = 16, updatable = false)
    private String decision;

    @Column(name = "evaluated_decision", length = 16, updatable = false)
    private String evaluatedDecision;

    @Column(name = "reason", length = 32, updatable = false)
    private String reason;

    @Column(name = "support_ref", length = 16, updatable = false)
    private String supportRef;

    @Column(name = "actor_type", nullable = false, length = 16, updatable = false)
    private String actorType;

    @Column(name = "actor_id", length = 255, updatable = false)
    private String actorId;

    @Column(name = "channel", length = 16, updatable = false)
    private String channel;

    @Column(name = "purpose", length = 16, updatable = false)
    private String purpose;

    @Column(name = "context", length = 16, updatable = false)
    private String context;

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Column(name = "ussd_session_id", length = 100, updatable = false)
    private String ussdSessionId;

    @Column(name = "ip_address", length = 64, updatable = false)
    private String ipAddress;

    @Column(name = "risk_score", updatable = false)
    private Integer riskScore;

    @Column(name = "features", columnDefinition = "TEXT", updatable = false)
    private String features;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;
}
