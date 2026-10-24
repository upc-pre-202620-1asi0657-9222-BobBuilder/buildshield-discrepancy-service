package pe.buildshield.discrepancy.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Cierre de una discrepancia escalada: la decisión final del administrador y cuándo la tomó. */
public record Resolution(String decision, UUID resolvedBy, Instant resolvedAt) {

    public Resolution {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(resolvedBy, "resolvedBy");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
    }
}
