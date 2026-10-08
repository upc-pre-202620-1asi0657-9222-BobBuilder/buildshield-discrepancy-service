package pe.buildshield.discrepancy.domain.model;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Copias locales que discrepancy mantiene con eventos: materiales y asignaciones de organization, y recepciones de
 * ordering. Nunca se consulta a otro servicio por REST.
 */
public interface LogisticsCopies {

    void upsertMaterial(UUID organizationId, MaterialSnapshot material, boolean active);

    Optional<MaterialSnapshot> material(UUID organizationId, UUID materialId);

    void assign(UUID organizationId, UUID assignmentId, UUID userId, UUID locationId, String locationType);

    void unassign(UUID assignmentId);

    /** Obras (WORKSITE) o almacenes (WAREHOUSE) asignados hoy al usuario. */
    Set<UUID> assignedLocations(UUID organizationId, UUID userId, String locationType);

    /** Guarda o completa la recepción; las líneas solo llegan con la confirmación. */
    void upsertReception(UUID organizationId, ReceptionSnapshot reception);

    Optional<ReceptionSnapshot> reception(UUID organizationId, UUID receptionId);
}
