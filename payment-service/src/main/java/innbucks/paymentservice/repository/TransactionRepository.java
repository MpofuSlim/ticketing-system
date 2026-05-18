package innbucks.paymentservice.repository;

import innbucks.paymentservice.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * JpaRepository over the {@code transactions} ledger. Kept minimal — today
 * only {@link TransactionService} writes through here; history / reconciliation
 * queries land as follow-up work and will be added as derived methods or
 * {@code @Query}-annotated finders when those endpoints arrive.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
}
