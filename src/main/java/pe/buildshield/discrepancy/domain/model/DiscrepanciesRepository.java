package pe.buildshield.discrepancy.domain.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Discrepancias de la organización del contexto. */
public interface DiscrepanciesRepository {

    Discrepancy save(Discrepancy discrepancy);

    Optional<Discrepancy> findById(UUID id);

    boolean existsForReceptionLine(UUID receptionLineId);

    /** De la más reciente a la más antigua. */
    List<Discrepancy> search(DiscrepancyFilter filter);
}
