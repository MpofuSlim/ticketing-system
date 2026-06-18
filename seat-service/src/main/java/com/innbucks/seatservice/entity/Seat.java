package com.innbucks.seatservice.entity;

import jakarta.persistence.*;
import lombok.*;
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
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "category_id", nullable = false)
    private SeatCategory category;

    @Column(name = "row_label", nullable = false)
    private String sectionLabel;

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

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        if (this.status == null) {
            this.status = SeatStatus.AVAILABLE;
        }
    }

    public enum SeatStatus {
        AVAILABLE,   // free to book
        LOCKED,      // temporarily held — lockExpiresAt is the TTL
        BOOKED       // permanently reserved after payment
    }
}
