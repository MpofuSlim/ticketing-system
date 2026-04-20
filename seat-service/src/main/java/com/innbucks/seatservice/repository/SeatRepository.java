package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
