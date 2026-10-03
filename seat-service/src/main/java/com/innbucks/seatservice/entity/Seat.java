package com.innbucks.seatservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(
        name = "seats",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"category_id", "row_label", "seat_number"}
        )
)
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // LAZY (a bare @ManyToOne is EAGER, which cost one category query per
    // distinct category on every seat query, read or not). open-in-view is
    // off, so a path that reads more than the category's id either fetches it
    // in its query (the SeatRepository finders SeatService renders from) or
    // reads it inside a transaction. getCategory().getId() never needs a
    // query — the reference already holds the id. Excluded from Lombok's
    // equals/hashCode/toString: they would initialise it (or throw outside a
    // session), and SeatCategory.seats points straight back here.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private SeatCategory category;

    @Column(name = "row_label", nullable = false)
    private String sectionLabel;

    // Optional per-section preview image URL. The same value is stamped on every
    // seat in a section at creation (sections aren't first-class rows here); the
    // read paths recover it by taking it from any seat in the section. Nullable.
    @Column(name = "section_image_url", length = 1024)
    private String sectionImageUrl;

    @Column(nullable = false)
    private Integer seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    // When the current LOCKED hold expires. Set whenever status transitions
    // to LOCKED; cleared on confirm/release. Authoritative — SeatLockReaper
    // sweeps rows where this is in the past without trusting Redis.
    @Column(name = "lock_expires_at")
    private LocalDateTime lockExpiresAt;

    @Version
    private Long version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Actor (organizer uuid, or JWT email fallback) that created this row,
     *  auto-stamped by JPA auditing. See JpaAuditingConfig. Null for legacy rows
     *  and system/unauthenticated writes. */
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    /** Actor that last updated this row (JPA auditing; equals createdBy on INSERT). */
    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        if (this.status == null) {
            this.status = SeatStatus.AVAILABLE;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public enum SeatStatus {
        AVAILABLE,   // free to book
        LOCKED,      // temporarily held — lockExpiresAt is the TTL
        BOOKED       // permanently reserved after payment
    }
}
