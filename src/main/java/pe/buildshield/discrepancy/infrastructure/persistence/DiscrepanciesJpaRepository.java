package pe.buildshield.discrepancy.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

interface DiscrepanciesJpaRepository extends JpaRepository<DiscrepancyJpaEntity, UUID>,
        JpaSpecificationExecutor<DiscrepancyJpaEntity> {

    boolean existsByReceptionLineId(UUID receptionLineId);
}
