package pe.buildshield.discrepancy.domain.model;

import java.math.BigDecimal;

/** Tipo de diferencia entre lo despachado y lo recibido (ER v4: SHORTAGE, SURPLUS o SHRINKAGE). */
public enum DiscrepancyType {
    SHORTAGE("Faltante"),
    SURPLUS("Sobrante"),
    SHRINKAGE("Merma");

    private final String label;

    DiscrepancyType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Sobrante si llegó más de lo despachado. Si llegó menos, merma cuando el material admite una pérdida (tolerancia
     * mayor que cero) y faltante cuando no admite ninguna.
     */
    public static DiscrepancyType of(BigDecimal dispatched, BigDecimal received, BigDecimal tolerancePercent) {
        if (received.compareTo(dispatched) > 0) {
            return SURPLUS;
        }
        return tolerancePercent != null && tolerancePercent.signum() > 0 ? SHRINKAGE : SHORTAGE;
    }
}
