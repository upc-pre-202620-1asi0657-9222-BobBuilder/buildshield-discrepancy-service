package pe.buildshield.discrepancy.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;

import java.util.Collection;
import java.util.UUID;

interface SpringDataDiscrepancyRepository extends JpaRepository<DiscrepancyJpaEntity, UUID>,
        JpaSpecificationExecutor<DiscrepancyJpaEntity> {

    boolean existsByReceptionLineId(UUID receptionLineId);

    boolean existsByReceptionIdAndMaterialIdAndTypeAndStatusIn(UUID receptionId, UUID materialId, DiscrepancyType type,
            Collection<DiscrepancyStatus> statuses);
}
