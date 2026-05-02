package com.innbucks.bookingservice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String userEmail;

    // Captured from the JWT's phoneNumber claim at booking time. Optional —
    // some JWTs (system users, older tokens) don't carry the claim, so the
    // booking is still valid without one. Indexed so `findByPhoneNumber*`
    // lookups don't full-scan.
    @Column
    private String phoneNumber;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false, unique = true)
    private String confirmationNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<BookingItem> items;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // When a PENDING booking's seat-hold runs out. The expiration scheduler
    // flips such bookings to CANCELLED and releases the seats. Null for
    // CONFIRMED (paid — no hold) and for legacy bookings created before the
    // hold model existed.
    private LocalDateTime expiresAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = BookingStatus.PENDING;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum BookingStatus {
        PENDING,
        CONFIRMED,
        CANCELLED
    }
}
