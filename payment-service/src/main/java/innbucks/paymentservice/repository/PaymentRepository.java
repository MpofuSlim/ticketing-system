package innbucks.paymentservice.repository;

import innbucks.paymentservice.entity.Payment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Payment}. Lookup by stable
 * {@code paymentReference} (the veengu-side idempotency key) is the primary
 * read for the reconciler and support-ticket workflows; lookup by
 * {@code idempotencyKey} backs the cache-eviction safety net.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByPaymentReference(String paymentReference);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * Pre-check companion to the {@code uq_payment_active_booking} partial
     * unique index: lets the service refuse a second payment for a booking
     * with a clean 409 instead of a constraint violation. The INDEX remains
     * the source of truth under concurrency — this is UX, not enforcement.
     */
    boolean existsByBookingIdAndStatusIn(UUID bookingId, Collection<Payment.PaymentStatus> statuses);

    /**
     * Reconciler sweep: rows stuck in a non-terminal state (PENDING /
     * IN_DOUBT) past the staleness threshold.
     */
    List<Payment> findByStatusInAndCreatedAtBefore(
            Collection<Payment.PaymentStatus> statuses, Instant cutoff, Pageable pageable);

    /** Reconciler sweep: money-moved-but-booking-unconfirmed rows to retry. */
    List<Payment> findByStatus(Payment.PaymentStatus status, Pageable pageable);

    /**
     * Replay lookup for the public {@code POST /payments} entry: when a
     * booking already has an active-or-successful payment, the endpoint
     * returns THAT row's receipt (idempotent-replay semantics — a double-tap
     * must never surface as an error to the FE).
     */
    Optional<Payment> findFirstByBookingIdAndStatusInOrderByCreatedAtDesc(
            UUID bookingId, Collection<Payment.PaymentStatus> statuses);
}
