package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByUserEmail(String userEmail);

    Optional<Booking> findByConfirmationNumber(String confirmationNumber);

    List<Booking> findByEventId(UUID eventId);

    // Phone-number lookup (most-recent first). One phone may have many
    // bookings across events / over time, so this returns a list rather than
    // an Optional. See GET /bookings/phone/{phoneNumber}.
    List<Booking> findByPhoneNumberOrderByCreatedAtDesc(String phoneNumber);

    // PENDING bookings whose seat hold has lapsed. Used by the expiration
    // scheduler to auto-cancel them and publish BookingCancelled events.
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = com.innbucks.bookingservice.entity.Booking.BookingStatus.PENDING
          AND b.expiresAt IS NOT NULL
          AND b.expiresAt < :now
    """)
    List<Booking> findExpiredPending(@Param("now") LocalDateTime now);
}
