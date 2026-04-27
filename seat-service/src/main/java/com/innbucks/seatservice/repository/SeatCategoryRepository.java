package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.SeatCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SeatCategoryRepository extends JpaRepository<SeatCategory, UUID> {

    List<SeatCategory> findByEventIdAndDeletedFalse(UUID eventId);

    boolean existsByEventIdAndNameAndDeletedFalse(UUID eventId, String name);

    @Modifying
    @Query("UPDATE SeatCategory c SET c.availableSeats = c.availableSeats - 1 " +
            "WHERE c.id = :id AND c.availableSeats > 0")
    int decrementAvailableSeats(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE SeatCategory c SET c.availableSeats = c.availableSeats + 1 " +
            "WHERE c.id = :id AND c.availableSeats < c.totalSeats")
    int incrementAvailableSeats(@Param("id") UUID id);
}
