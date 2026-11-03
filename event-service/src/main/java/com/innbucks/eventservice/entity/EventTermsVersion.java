package com.innbucks.eventservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One version of an event's terms and conditions (V16), append-only.
 *
 * <p>A booking records the version its buyer accepted, so the text of every
 * version must stay readable for as long as a booking names it: a version is
 * never updated or deleted, and removing an event's terms only clears
 * {@link Event#getTermsVersion()}.
 */
@Entity
@Immutable
@Table(name = "event_terms_versions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventTermsVersion {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", updatable = false)
    private String createdBy;
}
