package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.SeatCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SeatCategoryRepository extends JpaRepository<SeatCategory, UUID> {

    List<SeatCategory> findByEventIdAndDeletedFalse(UUID eventId);

    /** Several events' live categories in one query (GET /seat-categories/by-events). */
    List<SeatCategory> findByEventIdInAndDeletedFalse(Collection<UUID> eventIds);

    boolean existsByEventIdAndNameAndDeletedFalse(UUID eventId, String name);

    // Same duplicate-name guard as create, but excludes the category being
    // updated so renaming a category to its own current name isn't a conflict.
    boolean existsByEventIdAndNameAndDeletedFalseAndIdNot(UUID eventId, String name, UUID id);

    @Modifying
    @Query("UPDATE SeatCategory c SET c.availableSeats = c.availableSeats - 1 " +
            "WHERE c.id = :id AND c.availableSeats > 0")
    int decrementAvailableSeats(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE SeatCategory c SET c.availableSeats = c.availableSeats + 1 " +
            "WHERE c.id = :id AND c.availableSeats < c.totalSeats")
    int incrementAvailableSeats(@Param("id") UUID id);

    // A layout copy onto one event (SeatLayoutCopyService) takes this
    // transaction-scoped advisory lock FIRST, so two copies onto the same
    // target queue and the second finds the first's categories. Released at
    // commit or rollback. Nothing else takes it, and a copy takes one, so it
    // adds no cycle. Two targets sharing a key only queue.
    // pg_advisory_xact_lock returns void; called in FROM, it always runs.
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(CAST(:namespace AS integer), CAST(:eventKey AS integer))",
            nativeQuery = true)
    Integer lockEventLayout(@Param("namespace") int namespace, @Param("eventKey") int eventKey);
}
