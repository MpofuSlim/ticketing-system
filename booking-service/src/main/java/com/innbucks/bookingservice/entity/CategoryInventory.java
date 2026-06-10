package com.innbucks.bookingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Per-category ticket-inventory counter (GA model). One row per seat category;
 * {@code remaining} is the number of tickets still sellable in that category.
 *
 * <p>The authoritative oversell guard. Claimed by a guarded atomic decrement
 * ({@code CategoryInventoryRepository.tryClaim}) inside the booking-create
 * transaction, released on cancel/expire/reverse. Seeded lazily from
 * seat-service's {@code total_seats} on first booking for the category.
 *
 * <p>Deliberately has no FK to a categories table — seat categories live in
 * seat-service's database; this is booking-service's own projection of the
 * capacity it's responsible for selling.
 */
@Entity
@Table(name = "category_inventory")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryInventory {

    @Id
    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(name = "remaining", nullable = false)
    private int remaining;
}
