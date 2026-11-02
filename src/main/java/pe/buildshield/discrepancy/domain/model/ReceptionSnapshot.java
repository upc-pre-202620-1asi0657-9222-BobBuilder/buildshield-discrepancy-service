package pe.buildshield.discrepancy.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Recepción según los eventos de ordering; sus líneas solo se conocen desde {@code ReceptionConfirmed}. */
public record ReceptionSnapshot(UUID receptionId, UUID dispatchId, String manifestCode, UUID worksiteId,
        UUID warehouseId, List<Line> lines) {

    public record Line(UUID receptionLineId, UUID materialId, String sku, String materialName,
            BigDecimal shrinkagePercent, BigDecimal tolerancePercent) {
    }

    public Optional<Line> line(UUID materialId) {
        return lines.stream().filter(line -> line.materialId().equals(materialId)).findFirst();
    }

    /** Enlace de una discrepancia manual: sin línea, para no ocupar la de la automática (QAS10). */
    public Discrepancy.ReceptionLink manualLink() {
        return new Discrepancy.ReceptionLink(receptionId, null, dispatchId, manifestCode, worksiteId, warehouseId);
    }
}
