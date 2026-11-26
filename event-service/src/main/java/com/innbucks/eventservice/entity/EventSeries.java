package com.innbucks.eventservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A recurring event (V24, TIC-70): a schedule and the template event every
 * date is copied from. Times and dates are the market's local wall clock, as
 * the organizer typed them; each date's own times are stored in UTC like any
 * event's.
 */
@Entity
@Table(name = "event_series")
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSeries {

    /** Where a series stands. Values are never removed (the column holds every one ever stored). */
    public enum Status {
        /** Not live yet, or unpublished: no new dates are made. */
        DRAFT,
        /** Live: dates are made inside the booking window. */
        ACTIVE,
        /** Reserved for pausing a series (S2); nothing sets it yet. */
        PAUSED,
        /** The template was deleted: no new dates, ever. */
        ENDED
    }

    /** Assigned in code, so the template row can name its series in the same transaction. */
    @Id
    @Column(name = "series_id")
    private UUID seriesId;

    @Column(name = "template_event_id", nullable = false, updatable = false)
    private UUID templateEventId;

    @Column(name = "tenant_user_uuid", nullable = false, updatable = false)
    private UUID tenantUserUuid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /** {@code MONDAY}..{@code SUNDAY}. */
    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "days_of_week", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private List<String> daysOfWeek = new ArrayList<>();

    /** {@code HH:mm}, market-local. */
    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "start_times", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private List<String> startTimes = new ArrayList<>();

    /** {@code yyyy-MM-dd}, market-local: the schedule makes no date on these days. */
    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "skip_dates", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private List<String> skipDates = new ArrayList<>();

    /** {@code yyyy-MM-ddTHH:mm}, market-local: one-off starts besides the schedule. */
    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "extra_starts", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private List<String> extraStarts = new ArrayList<>();

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** The last day the schedule makes a date on; null = no end. */
    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "booking_window_days", nullable = false)
    private int bookingWindowDays;

    /** Sales open this many minutes before each date starts; null = as soon as the date exists. */
    @Column(name = "sales_open_minutes_before")
    private Integer salesOpenMinutesBefore;

    /** Sales close this many minutes before each date starts; null = they run until the date ends. */
    @Column(name = "sales_close_minutes_before")
    private Integer salesCloseMinutesBefore;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
