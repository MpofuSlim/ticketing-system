package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByCategoryId(UUID categoryId);
    List<Seat> findByCategoryIdIn(List<UUID> categoryIds);

    List<Seat> findByCategoryIdAndStatus(UUID categoryId, Seat.SeatStatus status);

    // A random sample of up to `limit` AVAILABLE seats in a category. Used by
    // booking-service to pick a seat WITHOUT pulling the entire available pool:
    // a six-figure category returned ~MBs of JSON per booking and blew past the
    // caller's 1s circuit-breaker timeout (the per-booking cost scaled with
    // total inventory). ORDER BY random() also spreads picks so concurrent
    // bookings rarely sample the same seat, which cuts double-booking 409s.
    // Native because JPQL has no random(); status is @Enumerated(STRING) so it
    // compares against the literal 'AVAILABLE'.
    @Query(value = "SELECT * FROM seats WHERE category_id = :categoryId "
            + "AND status = 'AVAILABLE' ORDER BY random() LIMIT :limit",
            nativeQuery = true)
    List<Seat> findRandomAvailableByCategory(@Param("categoryId") UUID categoryId,
                                             @Param("limit") int limit);

    Optional<Seat> findByCategoryIdAndSectionLabelAndSeatNumber(
            UUID categoryId, String sectionLabel, Integer seatNumber
    );

    // SELECT ... FOR UPDATE — serialises concurrent lock/confirm/release on the
    // same seat row so the read-then-write sequence is atomic at the DB level.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") UUID id);

    // Candidates for the reaper: LOCKED rows whose TTL has elapsed. Pageable
    // caps the batch so a long-untouched system doesn't load every stale seat
    // into one transaction.
    @Query("SELECT s.id FROM Seat s " +
            "WHERE s.status = com.innbucks.seatservice.entity.Seat.SeatStatus.LOCKED " +
            "AND s.lockExpiresAt IS NOT NULL " +
            "AND s.lockExpiresAt < :now")
    List<UUID> findExpiredLockIds(@Param("now") LocalDateTime now, Pageable pageable);
}
