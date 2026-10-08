package pe.buildshield.discrepancy.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Foto de evidencia: el archivo vive en el almacenamiento de evidencias; aquí solo su referencia. */
public record Evidence(UUID id, String storageKey, String url, String fileName, String contentType, long sizeBytes,
        UUID uploadedBy, Instant uploadedAt) {

    public Evidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(uploadedBy, "uploadedBy");
        Objects.requireNonNull(uploadedAt, "uploadedAt");
    }
}
