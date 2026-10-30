package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.EventScannerAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EventScannerAssignmentRepository extends JpaRepository<EventScannerAssignment, UUID> {

    List<EventScannerAssignment> findByScannerIdIn(Collection<UUID> scannerIds);

    boolean existsByScannerIdAndEventId(UUID scannerId, UUID eventId);

    @Modifying
    @Query("delete from EventScannerAssignment a where a.scannerId = :scannerId and a.eventId = :eventId")
    int deleteByScannerIdAndEventId(@Param("scannerId") UUID scannerId, @Param("eventId") UUID eventId);

    /**
     * Does assigning {@code scannerId} (on {@code phone}) to an event running
     * [{@code start}, {@code end}) overlap anything the number already scans?
     *
     * <p>"Already scans" is every other event of this scanner row, whatever its
     * status (its own events must never overlap each other), plus the events of
     * the number's OTHER rows that count: not switched off, not declined. Ended
     * and deleted events never clash. Windows are half-open, so an event that
     * starts as another ends is no clash. Times are the events' live UTC
     * columns, so an organizer's later edit is read as it now stands.
     *
     * <p>Call it after {@link EventScannerRepository#lockAllByPhoneNumber}, in
     * the same transaction, or two organizers can both pass it at once.
     */
    @Query(value = """
            SELECT COUNT(*) FROM event_scanner_assignments a
            JOIN event_scanners s ON s.scanner_id = a.scanner_id
            JOIN events e ON e.event_id = a.event_id
            WHERE s.phone_number = :phone
              AND (s.scanner_id = :scannerId
                   OR (s.disabled_at IS NULL AND s.declined_at IS NULL))
              AND a.event_id <> :eventId
              AND e.deleted = FALSE
              AND e.end_date_time > :now
              AND e.start_date_time < :end
              AND e.end_date_time > :start
            """, nativeQuery = true)
    long countClashes(@Param("phone") String phone,
                      @Param("scannerId") UUID scannerId,
                      @Param("eventId") UUID eventId,
                      @Param("start") LocalDateTime start,
                      @Param("end") LocalDateTime end,
                      @Param("now") LocalDateTime now);

    /**
     * Would {@code scannerId} start counting again (switched back on, or
     * invited again after a decline) with one of its upcoming events
     * overlapping an event of the number's other counting rows? Same rules and
     * same lock as {@link #countClashes}.
     */
    @Query(value = """
            SELECT COUNT(*) FROM event_scanner_assignments mine
            JOIN events me ON me.event_id = mine.event_id
            JOIN event_scanners os ON os.phone_number = :phone
                                  AND os.scanner_id <> :scannerId
                                  AND os.disabled_at IS NULL
                                  AND os.declined_at IS NULL
            JOIN event_scanner_assignments other ON other.scanner_id = os.scanner_id
                                                AND other.event_id <> mine.event_id
            JOIN events oe ON oe.event_id = other.event_id
            WHERE mine.scanner_id = :scannerId
              AND me.deleted = FALSE AND me.end_date_time > :now
              AND oe.deleted = FALSE AND oe.end_date_time > :now
              AND oe.start_date_time < me.end_date_time
              AND oe.end_date_time > me.start_date_time
            """, nativeQuery = true)
    long countClashesOnReturn(@Param("phone") String phone,
                              @Param("scannerId") UUID scannerId,
                              @Param("now") LocalDateTime now);
}
