package com.innbucks.bookingservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A customer asked to be told before an event's price goes up (V28). Keyed by
 * the phone on their session; {@link #unsubscribedAt} set means off.
 */
@Entity
@Table(name = "event_reminder_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventReminderSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "phone_number", nullable = false, updatable = false, length = 32)
    private String phoneNumber;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "unsubscribed_at")
    private LocalDateTime unsubscribedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public boolean isActive() {
        return unsubscribedAt == null;
    }
}
