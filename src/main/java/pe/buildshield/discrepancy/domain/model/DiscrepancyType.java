package pe.buildshield.discrepancy.domain.model;

/** Tipo de diferencia encontrada en la obra. */
public enum DiscrepancyType {
    SHORTAGE("Faltante"),
    DAMAGE("Daño"),
    EXCESS("Exceso"),
    WRONG_MATERIAL("Material equivocado");

    private final String label;

    DiscrepancyType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
