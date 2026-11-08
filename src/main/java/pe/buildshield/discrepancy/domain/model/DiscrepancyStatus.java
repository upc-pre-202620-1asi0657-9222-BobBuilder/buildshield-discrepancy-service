package pe.buildshield.discrepancy.domain.model;

/**
 * Estados: {@code OPEN → RESPONDED → ACCEPTED} (cierra) o {@code RESPONDED → ESCALATED → RESOLVED} (cierra).
 */
public enum DiscrepancyStatus {
    OPEN("Abierta"),
    RESPONDED("Respondida"),
    ESCALATED("Escalada"),
    ACCEPTED("Aceptada"),
    RESOLVED("Resuelta");

    private final String label;

    DiscrepancyStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean closed() {
        return this == ACCEPTED || this == RESOLVED;
    }
}
