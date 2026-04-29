package com.innbucks.eventservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_events_natural_key",
                columnNames = {"tenant_id", "title", "venue", "date_time"}
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

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String venue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Province province;

    @Embedded
    private Location location;

    @Column(nullable = false)
    private LocalDateTime dateTime;

    @Column(nullable = false)
    private Integer totalCapacity;

    @Column(nullable = false)
    private Integer availableTickets;

    // Banner image for the event. Mapped to LONGVARBINARY so it portably becomes
    // varbinary on H2 / bytea on Postgres (H2 in MODE=PostgreSQL rejects BLOB,
    // which is the default for @Lob byte[]). Lazy-loaded so list endpoints don't
    // pull the bytes into memory. Served via GET /events/{id}/banner; the
    // response DTO carries only the URL.
    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "banner_image")
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

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
