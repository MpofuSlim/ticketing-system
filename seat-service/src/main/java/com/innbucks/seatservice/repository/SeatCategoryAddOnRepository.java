package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.SeatCategoryAddOn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SeatCategoryAddOnRepository extends JpaRepository<SeatCategoryAddOn, UUID> {

    List<SeatCategoryAddOn> findByCategoryIdOrderByPositionAsc(UUID categoryId);

    List<SeatCategoryAddOn> findByCategoryIdInOrderByCategoryIdAscPositionAsc(Collection<UUID> categoryIds);
}
