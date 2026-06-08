package innbucks.paymentservice.repository;

import innbucks.paymentservice.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
