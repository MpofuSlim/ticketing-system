package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.SeatCategoryPriceTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SeatCategoryPriceTierRepository extends JpaRepository<SeatCategoryPriceTier, UUID> {

    /** One category's tiers, in the order they apply. */
    List<SeatCategoryPriceTier> findByCategoryIdOrderByPositionAsc(UUID categoryId);

    /** Several categories' tiers in one query, each category's in the order they apply. */
    List<SeatCategoryPriceTier> findByCategoryIdInOrderByCategoryIdAscPositionAsc(Collection<UUID> categoryIds);
}
