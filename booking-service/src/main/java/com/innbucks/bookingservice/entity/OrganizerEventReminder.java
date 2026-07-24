package com.innbucks.bookingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Exactly-once marker for the day-before ORGANIZER reminder (V21): one row per
 * event whose organizer has been sent — or, for already-started events,
 * silently skipped — the pre-event headline email. Attendee reminders track
 * per-booking on {@link Booking}; this is per-event.
 */
@Entity
@Table(name = "organizer_event_reminders")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OrganizerEventReminder {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;
}
