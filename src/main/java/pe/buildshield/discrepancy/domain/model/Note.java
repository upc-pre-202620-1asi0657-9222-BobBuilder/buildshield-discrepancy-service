package pe.buildshield.discrepancy.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Texto registrado en un paso de la discrepancia (respuesta, motivo del escalamiento o decisión), con su autor. */
public record Note(String text, UUID by, Instant at) {

    public Note {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(at, "at");
    }
}
