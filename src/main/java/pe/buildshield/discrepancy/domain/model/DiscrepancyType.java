package pe.buildshield.discrepancy.domain.model;

import java.math.BigDecimal;

/**
 * Tipo de diferencia. Las automáticas son SHORTAGE, SURPLUS o SHRINKAGE (ER v4); a mano se registra lo que ofrece el
 * formulario de la web: SHORTAGE, DAMAGE, EXCESS o WRONG_MATERIAL.
 */
public enum DiscrepancyType {
    SHORTAGE("Faltante", true),
    SURPLUS("Sobrante", false),
    SHRINKAGE("Merma", false),
    DAMAGE("Daño", true),
    EXCESS("Exceso", true),
    WRONG_MATERIAL("Material equivocado", true);

    private final String label;
    private final boolean manual;

    DiscrepancyType(String label, boolean manual) {
        this.label = label;
        this.manual = manual;
    }

    /** Si la obra puede registrarlo a mano. */
    public boolean manual() {
        return manual;
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
