package com.innbucks.bookingservice.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per booking domain event awaiting publication to Kafka. INSERTed
 * by {@link com.innbucks.bookingservice.event.BookingEventPublisher} inside
 * the same DB transaction as the booking write, so a rolled-back booking
 * can't leave a ghost outbox row.
 *
 * <p>Lifecycle:
 * <pre>
 *   pending -> published   (drainer sent to Kafka successfully)
 *   pending -> giving_up   (attempts >= MAX_ATTEMPTS, raise alert)
 * </pre>
 *
 * <p>{@code payload} is the Jackson-serialised event JSON; {@code event_class}
 * is the fully-qualified class name so the drainer can deserialise back
 * to the typed Java event before handing it to {@code KafkaTemplate<String, Object>}
 * — that round-trip preserves the wire format consumers already see today.
 */
@Entity
@Table(name = "event_outbox")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "event_key", nullable = false)
    private String eventKey;

    @Column(name = "event_class", nullable = false)
    private String eventClass;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.pending;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = now;
        }
        if (this.status == null) {
            this.status = Status.pending;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** Lowercase to match the VARCHAR check-constraint values in V8. */
    public enum Status {
        pending,
        published,
        giving_up
    }
}
