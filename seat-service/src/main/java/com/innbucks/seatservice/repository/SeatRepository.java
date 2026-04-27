package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByCategoryId(UUID categoryId);
    List<Seat> findByCategoryIdIn(List<UUID> categoryIds);

    List<Seat> findByCategoryIdAndStatus(UUID categoryId, Seat.SeatStatus status);

    Optional<Seat> findByCategoryIdAndSectionLabelAndSeatNumber(
            UUID categoryId, String sectionLabel, Integer seatNumber
    );

    // SELECT ... FOR UPDATE — serialises concurrent lock/confirm/release on the
    // same seat row so the read-then-write sequence is atomic at the DB level.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") UUID id);
}
