package innbucks.paymentservice.repository;

import innbucks.paymentservice.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only access to the {@link PaymentEvent} journal. Insert + read
 * only — there are deliberately no update/delete paths; the journal is
 * immutable by design.
 */
@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    List<PaymentEvent> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
