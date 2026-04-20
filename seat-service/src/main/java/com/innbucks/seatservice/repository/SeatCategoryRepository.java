package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.SeatCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SeatCategoryRepository extends JpaRepository<SeatCategory, UUID> {

    List<SeatCategory> findByEventIdAndDeletedFalse(UUID eventId);

    boolean existsByEventIdAndNameAndDeletedFalse(UUID eventId, String name);
}
