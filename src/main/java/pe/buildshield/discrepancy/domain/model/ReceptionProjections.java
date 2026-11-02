package pe.buildshield.discrepancy.domain.model;

import java.util.Optional;
import java.util.UUID;

/**
 * Copia local de las recepciones de ordering ({@code ReceptionOpened}, {@code ReceptionConfirmed}). Con ella se valida
 * y se completa un registro manual; nunca se consulta a otro servicio por REST.
 */
public interface ReceptionProjections {

    /** Guarda o completa la recepción: un evento posterior no borra lo que ya se sabía. */
    void save(UUID organizationId, ReceptionSnapshot reception);

    Optional<ReceptionSnapshot> find(UUID organizationId, UUID receptionId);
}
