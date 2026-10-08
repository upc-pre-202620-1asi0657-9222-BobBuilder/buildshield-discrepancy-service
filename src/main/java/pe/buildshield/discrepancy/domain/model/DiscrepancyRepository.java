package pe.buildshield.discrepancy.domain.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Discrepancias de la organización del contexto. */
public interface DiscrepancyRepository {

    Discrepancy save(Discrepancy discrepancy);

    Optional<Discrepancy> findById(UUID id);

    boolean existsForReceptionLine(UUID receptionLineId);

    /** ¿Hay una sin cerrar del mismo tipo para ese material y recepción? */
    boolean existsUnclosed(UUID receptionId, UUID materialId, DiscrepancyType type);

    /** De la más reciente a la más antigua. */
    List<Discrepancy> search(DiscrepancyFilter filter);
}
