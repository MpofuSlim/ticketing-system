package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.NumberSeries;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The writers of {@code category_number_series} (V34). Each is ONE statement
 * that carries its own guard, so two requests can never both pass a check the
 * other is about to invalidate. A plain {@link Repository}, so nothing can
 * {@code save} the read-only entity.
 */
public interface NumberSeriesRepository extends Repository<NumberSeries, UUID> {

    Optional<NumberSeries> findById(UUID categoryId);

    List<NumberSeries> findByEventIdOrderByCreatedAtAscCategoryIdAsc(UUID eventId);

    /** Which of these categories number their tickets: confirm's one look before it claims anything. */
    @Query("SELECT s.categoryId FROM NumberSeries s WHERE s.categoryId IN :categoryIds")
    List<UUID> findNumberedCategoryIds(@Param("categoryIds") Collection<UUID> categoryIds);

    /**
     * Creates the series, or replaces its terms while it has handed out nothing.
     * 1 = written; 0 = it exists and has assigned numbers (refused).
     */
    @Modifying
    @Query(value = """
            INSERT INTO category_number_series
                (category_id, event_id, label, prefix, start_value, padding, assigned_count, updated_by,
                 created_at, updated_at)
            VALUES (:categoryId, :eventId, :label, :prefix, :start, :padding, 0, :actor, :now, :now)
            ON CONFLICT (category_id) DO UPDATE SET
                event_id = EXCLUDED.event_id, label = EXCLUDED.label, prefix = EXCLUDED.prefix,
                start_value = EXCLUDED.start_value, padding = EXCLUDED.padding,
                updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
            WHERE category_number_series.assigned_count = 0
            """, nativeQuery = true)
    int upsertWhileUnused(@Param("categoryId") UUID categoryId, @Param("eventId") UUID eventId,
                          @Param("label") String label, @Param("prefix") String prefix,
                          @Param("start") int start, @Param("padding") int padding,
                          @Param("actor") String actor, @Param("now") LocalDateTime now);

    /** 1 = removed; 0 = no series, or it has assigned numbers. */
    @Modifying
    @Query(value = "DELETE FROM category_number_series WHERE category_id = :categoryId AND assigned_count = 0",
            nativeQuery = true)
    int deleteWhileUnused(@Param("categoryId") UUID categoryId);

    /**
     * Claims the next {@code count} numbers of a category's series in one
     * statement, under the row's lock: {@code [firstValue, label, prefix,
     * padding]}, or no row when the category has no series. Not
     * {@code @Modifying}: an UPDATE ... RETURNING yields a result set.
     */
    @Query(value = """
            UPDATE category_number_series
               SET assigned_count = assigned_count + :count, updated_at = :now
             WHERE category_id = :categoryId
            RETURNING start_value + assigned_count - :count, label, prefix, padding
            """, nativeQuery = true)
    List<Object[]> claimBlock(@Param("categoryId") UUID categoryId, @Param("count") int count,
                              @Param("now") LocalDateTime now);

    /**
     * Takes the series row's lock without changing it, so {@code assign-sold}
     * reads its candidates while no confirm can claim a block. Empty = no series.
     */
    @Query(value = "SELECT category_id FROM category_number_series WHERE category_id = :categoryId FOR UPDATE",
            nativeQuery = true)
    List<Object> lockSeries(@Param("categoryId") UUID categoryId);

    /**
     * Writes a block of numbers onto their tickets in one statement. Each
     * ticket is written once ({@code assigned_number IS NULL}); the arrays are
     * Postgres array literals of the ticket ids and the numbers, in step.
     * Numbers hold only letters, digits and hyphens, so the literal needs no
     * quoting.
     */
    @Modifying
    @Query(value = """
            UPDATE booking_items i
               SET assigned_number = v.num, number_label = :label
              FROM unnest(CAST(:ids AS uuid[]), CAST(:nums AS text[])) AS v(id, num)
             WHERE i.id = v.id AND i.assigned_number IS NULL
            """, nativeQuery = true)
    int writeNumbers(@Param("ids") String idsArrayLiteral, @Param("nums") String numsArrayLiteral,
                     @Param("label") String label);

    /**
     * The paid tickets of a category that have no number, in the order their
     * bookings were made and, within one, as the buyer listed them.
     */
    @Query(value = """
            SELECT i.id FROM booking_items i JOIN bookings b ON b.id = i.booking_id
             WHERE i.category_id = :categoryId AND b.status = 'CONFIRMED' AND i.assigned_number IS NULL
             ORDER BY b.created_at, b.id, i.seat_number, i.id
            """, nativeQuery = true)
    List<UUID> findUnnumberedPaidItemIds(@Param("categoryId") UUID categoryId);
}
