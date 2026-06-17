package com.innbucks.eventservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(
        name = "events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_events_natural_key",
                columnNames = {"tenant_id", "title", "venue", "start_date_time"}
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID eventId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /**
     * Stable cross-service organizer reference. Matches {@code users.user_uuid}
     * in user-service. Populated by {@link EventService#createEvent} on every
     * new INSERT (dual-write alongside {@link #tenantId}); backfilled for
     * pre-V6 rows by {@code TenantUserUuidBackfillRunner}. Nullable until the
     * backfill is complete and the FE has migrated off the email-based
     * {@code tenantId}; a future migration will flip this NOT NULL and drop
     * the email column.
     */
    @Column(name = "tenant_user_uuid")
    private UUID tenantUserUuid;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String venue;

    // Country the event belongs to. Not chosen per-event in the request — it is
    // stamped from the creating organizer's JWT `country` claim so events are
    // categorized by the organizer's registered country.
    @Column(nullable = false)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventCategory category;

    @Embedded
    private Location location;

    @Column(name = "start_date_time", nullable = false)
    private LocalDateTime startDateTime;

    @Column(name = "end_date_time", nullable = false)
    private LocalDateTime endDateTime;

    @Column(nullable = false)
    private Integer totalCapacity;

    @Column(nullable = false)
    private Integer availableTickets;

    // Banner image bytes. Declared as BYTEA — Postgres has no length cap that
    // would truncate real-world images, unlike the default Hibernate varbinary
    // mapping. Lazy-loaded so list endpoints don't pull bytes into memory;
    // clients fetch the bytes via GET /events/{id}/banner using the bannerUrl
    // on the response.
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "banner_image", columnDefinition = "BYTEA")
    private byte[] bannerImage;

    @Column(name = "banner_content_type")
    private String bannerContentType;

    @Version
    private Long version;

    @Column(nullable = false)
    private boolean deleted = false;

    // Whether the event is still bookable/visible. Flipped to false by the
    // tenant (or by a future scheduler) when the event ends.
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // Admin moderation flag, independent of `active`. A SUPER_ADMIN sets this
    // true via PUT /events/{id}/reject (which also flips active=false) to keep
    // an event out of every public bookable listing — /events/active,
    // /events/search, /events/by-country — while leaving it discoverable in the
    // inactive listing so an admin can later approve it. PUT /events/{id}/approve
    // clears it. activateEvent() refuses to publish while this is true, so the
    // invariant active=true => rejected=false always holds. Defaults false.
    @Column(nullable = false)
    @Builder.Default
    private boolean rejected = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
