package com.innbucks.seatservice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
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

    // e.g. "A", "B", "C" (section)
    @Column(name = "row_label", nullable = false)
    private String sectionLabel;

    // e.g. 1, 2, 3
    @Column(nullable = false)
    private Integer seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = SeatStatus.AVAILABLE;
        }
    }

    public enum SeatStatus {
        AVAILABLE,   // free to book
        LOCKED,      // temporarily held — in-memory TTL controls this
        BOOKED       // permanently reserved after payment
    }
}
