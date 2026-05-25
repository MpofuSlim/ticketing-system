package com.innbucks.seatservice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "seat_categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Which event this category belongs to
    @Column(nullable = false)
    private UUID eventId;

    // e.g. "VIP", "General", "Backstage"
    @Column(nullable = false)
    private String name;

    private String description;

    // Price specific to this category
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer totalSeats;

    @Column(nullable = false)
    private Integer availableSeats;

    // Soft delete
    @Column(nullable = false)
    private boolean deleted = false;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL)
    private List<Seat> seats;

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
