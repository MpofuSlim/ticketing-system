package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.entity.OrganizerEventReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface OrganizerEventReminderRepository extends JpaRepository<OrganizerEventReminder, UUID> {

    // Claims an event's organizer reminder: 1 when THIS call wrote the marker
    // row, 0 when one already existed. ON CONFLICT on the event_id primary key
    // is what makes the claim exact — save() would merge (SELECT, then INSERT
    // or UPDATE), and an UPDATE of an existing row would read as a fresh claim.
    // Must run inside a caller's transaction.
    @Modifying
    @Query(value = """
        INSERT INTO organizer_event_reminders (event_id, sent_at)
        VALUES (:eventId, :sentAt)
        ON CONFLICT (event_id) DO NOTHING
        """, nativeQuery = true)
    int claim(@Param("eventId") UUID eventId, @Param("sentAt") LocalDateTime sentAt);
}
