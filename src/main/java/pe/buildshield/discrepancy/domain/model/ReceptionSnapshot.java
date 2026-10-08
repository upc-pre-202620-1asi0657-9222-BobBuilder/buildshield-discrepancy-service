package pe.buildshield.discrepancy.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Recepción según los eventos de ordering, con sus líneas si ya se confirmó. */
public record ReceptionSnapshot(UUID receptionId, UUID dispatchId, String manifestCode, UUID worksiteId,
        UUID warehouseId, boolean confirmed, List<Line> lines) {

    public record Line(UUID receptionLineId, UUID materialId, String sku, String materialName, BigDecimal dispatched,
            BigDecimal received, BigDecimal shrinkagePercent, BigDecimal tolerancePercent) {
    }

    public java.util.Optional<Line> line(UUID materialId) {
        return lines.stream().filter(line -> line.materialId().equals(materialId)).findFirst();
    }

    public Discrepancy.ReceptionLink link(UUID receptionLineId) {
        return new Discrepancy.ReceptionLink(receptionId, receptionLineId, dispatchId, manifestCode, worksiteId,
                warehouseId);
    }
}
