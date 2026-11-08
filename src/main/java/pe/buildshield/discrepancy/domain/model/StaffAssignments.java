package pe.buildshield.discrepancy.domain.model;

import java.util.Set;
import java.util.UUID;

/**
 * Copia local de las asignaciones de organization ({@code StaffAssigned}, {@code StaffUnassigned}): qué obras y
 * almacenes ve cada encargado. Nunca se consulta a otro servicio por REST.
 */
public interface StaffAssignments {

    void assign(UUID organizationId, UUID assignmentId, UUID userId, UUID locationId, String locationType);

    void unassign(UUID assignmentId);

    /** Obras (WORKSITE) o almacenes (WAREHOUSE) asignados hoy al usuario. */
    Set<UUID> assignedLocations(UUID organizationId, UUID userId, String locationType);
}
