package com.innbucks.bookingservice.loyalty;

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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One row per failed {@code loyalty.earn} attempt from
 * {@link com.innbucks.bookingservice.service.BookingService#applyLoyalty}.
 *
 * <p>Lifecycle:
 * <pre>
 *   pending -> succeeded   (next retry succeeds)
 *   pending -> giving_up   (attempts >= MAX_ATTEMPTS, raise alert)
 * </pre>
 *
 * <p>Drained by {@link LoyaltyEarnRetryJob} every minute. The retry job is
 * ShedLock-guarded so only one pod processes each tick — duplicate
 * loyalty.earn calls for the same row would invert the whole point of the
 * mechanism (we'd over-credit the customer's wallet instead of correcting
 * the original miss).
 */
@Entity
@Table(name = "loyalty_earn_retry")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyEarnRetry {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    // Identity + attribution for the ticketing earn call. customerEmail/tenantId
    // are legacy (nullable now) — superseded by these for new rows.
    @Column(name = "organizer_uuid")
    private UUID organizerUuid;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "cash_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal cashAmount;

    @Column(name = "reference", nullable = false)
    private String reference;

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
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
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
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** Lowercase to match the VARCHAR check constraint values in V7. */
    public enum Status {
        pending,
        succeeded,
        giving_up
    }
}
