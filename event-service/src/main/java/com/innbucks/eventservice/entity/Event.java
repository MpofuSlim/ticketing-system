package com.innbucks.eventservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@EntityListeners(AuditingEntityListener.class)
// The legacy email-based tenant_id column and its (tenant_id, title, venue,
// start_date_time) unique constraint were dropped in V8 once the backfill of
// tenant_user_uuid completed. The duplicate-create guard now lives at the
// application layer
// (existsByTenantUserUuidAndTitleAndVenueAndStartDateTimeAndDeletedFalse).
@Table(name = "events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID eventId;

    /**
     * Stable cross-service organizer reference. Matches {@code users.user_uuid}
     * in user-service. The sole identifier of the owning organizer now that the
     * email-as-tenant-id pattern has been removed (column tenant_id dropped in
     * V8). Set from the creating organizer's JWT on every INSERT; the backfill
     * has populated it on all legacy rows.
     */
    @Column(name = "tenant_user_uuid")
    private UUID tenantUserUuid;

    @Column(nullable = false)
    private String title;

    // Short settlement tag ([A-Z0-9]{3,12}) embedded by payment-service in every
    // InnBucks code-generation reference (TKZ-<CODE>-<shortId>) so the merchant
    // statement groups per event. Auto-derived from the title at creation when
    // not supplied; editable via PUT. Nullable for pre-existing events.
    @Column(name = "settlement_code", length = 12)
    private String settlementCode;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String venue;

    // Country the event belongs to. Not chosen per-event in the request — it is
    // stamped from the creating organizer's JWT `country` claim so events are
    // categorized by the organizer's registered country.
    @Column(nullable = false)
    private String country;

    // length must match the VARCHAR the migrations maintain (V12 widened
    // 20 -> 30; the CHECK constraint chk_events_category pins the values).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
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

    // The banner's BYTES (events.banner_image, BYTEA, up to 10 MB) are
    // deliberately NOT mapped here. A @Basic(fetch = LAZY) byte[] is only lazy
    // with Hibernate bytecode enhancement, which this service does not run, so
    // it used to be selected by every query that loads an Event, list pages
    // included. They are read and written only through EventRepository's
    // banner queries (findBannerImage / writeBannerImage). The column stays on
    // events, where Postgres keeps large values out of line, so a query that
    // does not name it never reads them. Don't map it back:
    // EventBannerNotMappedTest fails if a field is bound to that column.
    // The content type stays mapped: it is small, and bannerUrl is derived
    // from it.
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

    // When ticket sales open (UTC, V15). Null = on sale as soon as the event is
    // published. Visibility is still `active`/`rejected`: a published event
    // before this time is listed ("On sale from ...") but cannot be booked.
    // See SalesStatus for the one rule every reader applies.
    @Column(name = "sales_start_at")
    private LocalDateTime salesStartAt;

    // The review step (V17). The status is derived from these and `rejected`,
    // never stored (ReviewStatus.of). approvedAt set = allowed to go live;
    // approvedBy null = went live without a review (step off, or live before
    // V17). rejectionReason = what staff told the organizer, kept until the
    // event is resubmitted or approved.
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by", length = 254)
    private String approvedBy;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    // The terms-and-conditions version a buyer must accept now (V16); null =
    // no terms. The text of every version is in event_terms_versions.
    @Column(name = "terms_version")
    private Integer termsVersion;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * Stable {@code user_uuid} (or the JWT subject/email when a token carries no
     * uuid claim) of the principal that created this row — auto-stamped by JPA
     * auditing on INSERT, never updated. See {@code JpaAuditingConfig}. Null for
     * legacy rows and any system/unauthenticated write.
     */
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    /** Principal that last updated this row, auto-stamped by JPA auditing on
     *  every UPDATE (and set equal to {@code createdBy} on INSERT). */
    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
