package pe.buildshield.discrepancy.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Material según la copia local de organization. */
public record MaterialSnapshot(UUID materialId, String sku, String name, BigDecimal tolerancePercent) {
}
