package innbucks.paymentservice.repository;

import innbucks.paymentservice.entity.ReconRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Read side of the settlement-reconciliation report (newest first). */
@Repository
public interface ReconRunRepository extends JpaRepository<ReconRun, UUID> {

    List<ReconRun> findTop30ByOrderByCreatedAtDesc();
}
