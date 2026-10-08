package pe.buildshield.discrepancy.infrastructure.persistence;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyFilter;
import pe.buildshield.discrepancy.domain.model.DiscrepancyRepository;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;
import pe.buildshield.discrepancy.shared.error.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** El filtro multiempresa limita toda lectura a la organización del contexto. */
@Repository
class JpaDiscrepancyRepository implements DiscrepancyRepository {

    private static final EnumSet<DiscrepancyStatus> UNCLOSED = EnumSet.of(DiscrepancyStatus.OPEN,
            DiscrepancyStatus.RESPONDED, DiscrepancyStatus.ESCALATED);

    private final SpringDataDiscrepancyRepository jpa;

    JpaDiscrepancyRepository(SpringDataDiscrepancyRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Discrepancy save(Discrepancy discrepancy) {
        if (discrepancy.id() == null) {
            return jpa.saveAndFlush(new DiscrepancyJpaEntity(discrepancy)).toDomain();
        }
        DiscrepancyJpaEntity entity = jpa.findById(discrepancy.id()).orElseThrow(() ->
                new ResourceNotFoundException("La discrepancia " + discrepancy.id() + " no existe"));
        if (!Objects.equals(entity.getVersion(), discrepancy.version())) {
            throw new ObjectOptimisticLockingFailureException(DiscrepancyJpaEntity.class, discrepancy.id());
        }
        entity.apply(discrepancy);
        return jpa.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<Discrepancy> findById(UUID id) {
        return jpa.findById(id).map(DiscrepancyJpaEntity::toDomain);
    }

    @Override
    public boolean existsForReceptionLine(UUID receptionLineId) {
        return jpa.existsByReceptionLineId(receptionLineId);
    }

    @Override
    public boolean existsUnclosed(UUID receptionId, UUID materialId, DiscrepancyType type) {
        return jpa.existsByReceptionIdAndMaterialIdAndTypeAndStatusIn(receptionId, materialId, type, UNCLOSED);
    }

    @Override
    public List<Discrepancy> search(DiscrepancyFilter filter) {
        Specification<DiscrepancyJpaEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.worksiteId() != null) {
                predicates.add(cb.equal(root.get("worksiteId"), filter.worksiteId()));
            }
            if (filter.dispatchId() != null) {
                predicates.add(cb.equal(root.get("dispatchId"), filter.dispatchId()));
            }
            if (filter.worksiteScope() != null) {
                predicates.add(root.get("worksiteId").in(filter.worksiteScope()));
            }
            if (filter.warehouseScope() != null) {
                predicates.add(root.get("warehouseId").in(filter.warehouseScope()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return jpa.findAll(spec, Sort.by(Sort.Order.desc("openedAt"), Sort.Order.desc("id"))).stream()
                .map(DiscrepancyJpaEntity::toDomain).toList();
    }
}
