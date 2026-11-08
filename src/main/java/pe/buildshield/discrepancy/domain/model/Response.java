package pe.buildshield.discrepancy.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Paso registrado en una discrepancia ({@code discrepancy_responses}): la respuesta del almacén, la aceptación de la
 * obra, el motivo del escalamiento o la decisión del administrador, con su autor.
 */
public record Response(UUID id, Decision decision, UUID authorId, String comment, Instant createdAt) {

    /** Qué se decidió en el paso. */
    public enum Decision {
        RESPOND, ACCEPT, ESCALATE, RESOLVE
    }

    public Response {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(authorId, "authorId");
        Objects.requireNonNull(comment, "comment");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
