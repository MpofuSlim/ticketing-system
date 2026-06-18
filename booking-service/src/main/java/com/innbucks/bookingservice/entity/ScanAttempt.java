package com.innbucks.bookingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per ticket-scan attempt — see V15__scan_attempts.sql for the
 * design rationale and the queries this table serves.
 *
 * <p>Written by {@code TicketScanService.scan()} for EVERY outcome, not
 * just the happy path, so the organizer dashboard can answer "who
 * scanned what, when, with what outcome". Identity fields are
 * denormalised at scan time so an audit row stays stable even if the
 * scanning user is later renamed or soft-disabled in user-service.
 *
 * <p>{@code attemptedAt} is an {@link Instant} (UTC, wall-clock event
 * time) rather than {@code LocalDateTime} — these rows describe
 * wall-clock events shared across the fleet, the same way loyalty's
 * and payment's audit rows do, and the column is {@code timestamptz}
 * to match. {@code client_ip} maps as {@link String} on the Java side
 * (Hibernate writes it through to Postgres {@code INET} via
 * {@code columnDefinition}).
 */
@Entity
@Table(name = "scan_attempts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanAttempt {

    @Id
    private UUID id;

    @Column(name = "attempted_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant attemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private Outcome outcome;

    @Column(name = "ticket_number", nullable = false)
    private String ticketNumber;

    @Column(name = "booking_item_id")
    private UUID bookingItemId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "scanner_user_uuid")
    private UUID scannerUserUuid;

    @Column(name = "scanner_email")
    private String scannerEmail;

    @Column(name = "scanner_display_name")
    private String scannerDisplayName;

    @Column(name = "scanner_organizer_uuid")
    private UUID scannerOrganizerUuid;

    @Column(name = "correlation_id")
    private String correlationId;

    /** Postgres INET column, persisted as a String — Hibernate's String->INET
     *  coercion handles canonical IPv4 / IPv6 forms. Kept server-side: this
     *  field is NOT exposed via ScanAttemptDTO. */
    @Column(name = "client_ip", columnDefinition = "INET")
    private String clientIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "country")
    private String country;

    /**
     * Mirrors the CHECK constraint in V15__scan_attempts.sql exactly — adding a
     * value here without adding it to the migration (and vice versa) will fail
     * the insert at the DB layer.
     */
    public enum Outcome {
        ALLOWED,
        ALREADY_REDEEMED,
        WRONG_ORGANIZER,
        NOT_ASSIGNED_TO_EVENT,
        TICKET_NOT_FOUND,
        BOOKING_NOT_CONFIRMED
    }
}
