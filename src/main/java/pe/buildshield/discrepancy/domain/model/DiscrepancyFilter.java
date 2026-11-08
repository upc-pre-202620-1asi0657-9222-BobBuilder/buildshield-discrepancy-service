package pe.buildshield.discrepancy.domain.model;

import java.util.Set;
import java.util.UUID;

/**
 * Filtros de la lista y alcance del usuario. Un alcance nulo no restringe; un alcance vacío no deja ver nada.
 *
 * @param worksiteScope  obras que puede ver (encargado de obra) o nulo
 * @param warehouseScope almacenes que puede ver (encargado de almacén) o nulo
 */
public record DiscrepancyFilter(DiscrepancyStatus status, UUID worksiteId, UUID dispatchId, Set<UUID> worksiteScope,
        Set<UUID> warehouseScope) {

    public boolean seesNothing() {
        return (worksiteScope != null && worksiteScope.isEmpty()) || (warehouseScope != null && warehouseScope.isEmpty());
    }
}
