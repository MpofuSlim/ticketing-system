package com.innbucks.bookingservice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "booking_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(nullable = false)
    private UUID seatId;

    @Column(nullable = false)
    private UUID categoryId;

    @Column(nullable = false)
    private String rowLabel;

    @Column(nullable = false)
    private Integer seatNumber;

    @Column(nullable = false)
    private String categoryName;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtBooking;

    // e.g. 20260419-48291X — unique per seat
    @Column(nullable = false, unique = true)
    private String ticketNumber;
}
