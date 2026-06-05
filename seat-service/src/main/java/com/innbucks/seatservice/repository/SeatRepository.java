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

    // Indexed random sampling of AVAILABLE seats (see SeatService.getAvailableSeats(id,limit)
    // and V6's idx_seats_category_status_id). Seat PKs are random UUIDs, so taking the
    // first `limit` available seats with id >= a random pivot yields a random window in
    // O(log N + limit) — no full scan or sort, unlike the ORDER BY random() it replaced,
    // which scaled O(N) with inventory and tripped the caller's circuit breaker under load.
    // findAvailableBeforePivot wraps past the smallest ids when the pivot lands near the top.
    @Query("SELECT s FROM Seat s WHERE s.category.id = :categoryId "
            + "AND s.status = com.innbucks.seatservice.entity.Seat.SeatStatus.AVAILABLE "
            + "AND s.id >= :pivot ORDER BY s.id")
    List<Seat> findAvailableFromPivot(@Param("categoryId") UUID categoryId,
                                      @Param("pivot") UUID pivot,
                                      Pageable pageable);

    @Query("SELECT s FROM Seat s WHERE s.category.id = :categoryId "
            + "AND s.status = com.innbucks.seatservice.entity.Seat.SeatStatus.AVAILABLE "
            + "AND s.id < :pivot ORDER BY s.id")
    List<Seat> findAvailableBeforePivot(@Param("categoryId") UUID categoryId,
                                        @Param("pivot") UUID pivot,
                                        Pageable pageable);

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
