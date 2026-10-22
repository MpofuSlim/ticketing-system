package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.SeatCategoryPriceChange;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/** Append-only by shape: save and read, nothing that updates or deletes. */
public interface SeatCategoryPriceChangeRepository extends Repository<SeatCategoryPriceChange, UUID> {

    SeatCategoryPriceChange save(SeatCategoryPriceChange change);

    /** A category's last 200 changes, newest first. */
    List<SeatCategoryPriceChange> findTop200ByCategoryIdOrderByChangedAtDesc(UUID categoryId);
}
