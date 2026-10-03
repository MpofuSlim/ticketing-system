package com.innbucks.seatservice.repository;

import com.innbucks.seatservice.entity.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    // Seat.category is LAZY and open-in-view is off. The finders SeatService
    // renders SeatResponseDTOs from (category id + NAME) fetch the category in
    // the same query; a ManyToOne fetch is a plain join, safe with a limit.
    @EntityGraph(attributePaths = "category")
    List<Seat> findByCategoryId(UUID categoryId);

    @EntityGraph(attributePaths = "category")
    List<Seat> findByCategoryIdAndStatus(UUID categoryId, Seat.SeatStatus status);

    /** A seat with its category, for the S2S lookup and the confirm response. */
    @EntityGraph(attributePaths = "category")
    Optional<Seat> findWithCategoryById(UUID id);

    /**
     * Seats per section for the given categories, without loading a seat row:
     * the public category listing (every {@code GET /events/{id}} reaches it)
     * used to load every seat of the event just to count them. Sections come
     * back per category in the order they were CREATED (the order the
     * organizer listed them; seats are stamped in that order at creation),
     * label as the tie-break. Every seat in a section carries the same image
     * (stamped at creation), so MAX picks it — NULL only when the section has
     * none.
     */
    @Query("SELECT s.category.id AS categoryId, s.sectionLabel AS sectionLabel, "
            + "COUNT(s) AS seatCount, MAX(s.sectionImageUrl) AS imageUrl "
            + "FROM Seat s WHERE s.category.id IN :categoryIds "
            + "GROUP BY s.category.id, s.sectionLabel "
            + "ORDER BY MIN(s.createdAt), s.sectionLabel")
    List<SectionCount> countSections(@Param("categoryIds") Collection<UUID> categoryIds);

    interface SectionCount {
        UUID getCategoryId();
        String getSectionLabel();
        Long getSeatCount();
        String getImageUrl();
    }

    // Indexed random sampling of AVAILABLE seats (see SeatService.getAvailableSeats(id,limit)
    // and V6's idx_seats_category_status_id). Seat PKs are random UUIDs, so taking the
    // first `limit` available seats with id >= a random pivot yields a random window in
    // O(log N + limit) — no full scan or sort, unlike the ORDER BY random() it replaced,
    // which scaled O(N) with inventory and tripped the caller's circuit breaker under load.
    // findAvailableBeforePivot wraps past the smallest ids when the pivot lands near the top.
    @Query("SELECT s FROM Seat s JOIN FETCH s.category WHERE s.category.id = :categoryId "
            + "AND s.status = com.innbucks.seatservice.entity.Seat.SeatStatus.AVAILABLE "
            + "AND s.id >= :pivot ORDER BY s.id")
    List<Seat> findAvailableFromPivot(@Param("categoryId") UUID categoryId,
                                      @Param("pivot") UUID pivot,
                                      Pageable pageable);

    @Query("SELECT s FROM Seat s JOIN FETCH s.category WHERE s.category.id = :categoryId "
            + "AND s.status = com.innbucks.seatservice.entity.Seat.SeatStatus.AVAILABLE "
            + "AND s.id < :pivot ORDER BY s.id")
    List<Seat> findAvailableBeforePivot(@Param("categoryId") UUID categoryId,
                                        @Param("pivot") UUID pivot,
                                        Pageable pageable);

    Optional<Seat> findByCategoryIdAndSectionLabelAndSeatNumber(
            UUID categoryId, String sectionLabel, Integer seatNumber
    );

    // SELECT ... FOR UPDATE — serialises concurrent lock/confirm/release on the
    // same seat row so the read-then-write sequence is atomic at the DB level.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") UUID id);

    // Candidates for the reaper: LOCKED rows whose TTL has elapsed. Pageable
    // caps the batch so a long-untouched system doesn't load every stale seat
    // into one transaction.
    @Query("SELECT s.id FROM Seat s " +
            "WHERE s.status = com.innbucks.seatservice.entity.Seat.SeatStatus.LOCKED " +
            "AND s.lockExpiresAt IS NOT NULL " +
            "AND s.lockExpiresAt < :now")
    List<UUID> findExpiredLockIds(@Param("now") LocalDateTime now, Pageable pageable);
}
