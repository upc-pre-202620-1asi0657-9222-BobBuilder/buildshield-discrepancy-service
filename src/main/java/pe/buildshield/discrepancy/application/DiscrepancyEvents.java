package pe.buildshield.discrepancy.application;

import pe.buildshield.discrepancy.domain.model.Discrepancy;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Eventos de integración que publica discrepancy en {@code buildshield.discrepancy} (payload del sobre). Los nombres
 * de los campos son el contrato con notification y audit: no se renombran.
 */
public final class DiscrepancyEvents {

    public static final String OPENED = "DiscrepancyOpened";
    public static final String RESPONDED = "DiscrepancyResponded";
    public static final String ACCEPTED = "DiscrepancyAccepted";
    public static final String ESCALATED = "DiscrepancyEscalated";
    public static final String RESOLVED = "DiscrepancyResolved";
    public static final String DISCREPANCY = "DISCREPANCY";

    private DiscrepancyEvents() {
    }

    /**
     * Estado de la discrepancia tras el cambio.
     *
     * @param text la observación al abrir, la respuesta, el motivo del escalamiento o la decisión, según el evento
     */
    public record DiscrepancyChanged(UUID discrepancyId, UUID receptionId, UUID receptionLineId, UUID dispatchId,
            String manifestCode, UUID worksiteId, UUID warehouseId, UUID materialId, String sku, String materialName,
            String type, BigDecimal quantity, String status, String origin, UUID openedBy, String text) {

        static DiscrepancyChanged of(Discrepancy discrepancy, String text) {
            var reception = discrepancy.reception();
            var material = discrepancy.material();
            return new DiscrepancyChanged(discrepancy.id(), reception.receptionId(), reception.receptionLineId(),
                    reception.dispatchId(), reception.manifestCode(), reception.worksiteId(), reception.warehouseId(),
                    material.materialId(), material.sku(), material.name(), discrepancy.type().name(),
                    discrepancy.quantity(), discrepancy.status().name(), discrepancy.origin().name(),
                    discrepancy.openedBy(), text);
        }
    }
}
