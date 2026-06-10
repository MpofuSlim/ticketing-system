package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.CategoryInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Atomic per-category ticket-inventory operations (GA model). All mutations
 * are guarded single-statement UPDATEs so concurrent bookings serialise on the
 * row lock and {@code remaining} can never go negative.
 */
public interface CategoryInventoryRepository extends JpaRepository<CategoryInventory, UUID> {

    /**
     * Seed a category's counter exactly once. {@code remaining} is set to the
     * supplied value only if no row exists yet; a concurrent seeder that lost
     * the race is a no-op (ON CONFLICT DO NOTHING). Callers compute the seed as
     * {@code total_seats - existing_active_items} so an already-partially-booked
     * category (or one migrated in with prior bookings) seeds to the correct
     * remainder, not to full capacity.
     *
     * <p>Native query: {@code ON CONFLICT} is Postgres-specific. The fleet's
     * integration tests run on real Postgres (Testcontainers), so this is
     * exercised faithfully; unit tests mock this repository.
     */
    @Modifying
    @Query(value = "INSERT INTO category_inventory (category_id, remaining) "
            + "VALUES (:categoryId, :remaining) ON CONFLICT (category_id) DO NOTHING",
            nativeQuery = true)
    int seedIfAbsent(@Param("categoryId") UUID categoryId, @Param("remaining") int remaining);

    /**
     * Atomically claim {@code qty} tickets. Returns 1 when the claim succeeded
     * (enough remained), 0 when the category is sold out / under-stocked for
     * this request. The {@code remaining >= :qty} guard is what makes
     * overselling structurally impossible — the row lock serialises concurrent
     * claims and the guard rejects the one that would underflow.
     */
    @Modifying
    @Query("UPDATE CategoryInventory c SET c.remaining = c.remaining - :qty "
            + "WHERE c.categoryId = :categoryId AND c.remaining >= :qty")
    int tryClaim(@Param("categoryId") UUID categoryId, @Param("qty") int qty);

    /**
     * Return {@code qty} tickets to a category (booking cancelled / expired /
     * reversed). No upper clamp: releases only ever return tickets a prior
     * claim removed, and the one-way booking status transitions (PENDING/
     * CONFIRMED -> CANCELLED happens once) make a double-release impossible on
     * the normal paths.
     */
    @Modifying
    @Query("UPDATE CategoryInventory c SET c.remaining = c.remaining + :qty "
            + "WHERE c.categoryId = :categoryId")
    int release(@Param("categoryId") UUID categoryId, @Param("qty") int qty);
}
