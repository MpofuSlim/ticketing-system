package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.SeatCategoryPriceTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SeatCategoryPriceTierRepository extends JpaRepository<SeatCategoryPriceTier, UUID> {

    /** One category's tiers, in the order they apply. */
    List<SeatCategoryPriceTier> findByCategoryIdOrderByPositionAsc(UUID categoryId);

    /** Categories with a tier ending in {@code [from, to)} (UTC), for the tier-ending reminders. */
    @org.springframework.data.jpa.repository.Query("""
        SELECT DISTINCT t.categoryId FROM SeatCategoryPriceTier t
        WHERE t.endsAt >= :from AND t.endsAt < :to
    """)
    List<UUID> findCategoryIdsWithTierEndingBetween(
            @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);

    /** Several categories' tiers in one query, each category's in the order they apply. */
    List<SeatCategoryPriceTier> findByCategoryIdInOrderByCategoryIdAscPositionAsc(Collection<UUID> categoryIds);
}
