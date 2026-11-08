package pe.buildshield.discrepancy.domain.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Foto de evidencia: el archivo vive en el almacenamiento de evidencias; {@code photoUrl} es su clave relativa
 * ({@code organización/discrepancia/evidencia.ext}). El tipo de la imagen se deduce de la extensión.
 */
public record Evidence(UUID id, String photoUrl, Instant uploadedAt) {

    public static final int MAX_PHOTO_URL = 255;

    private static final Map<String, String> CONTENT_TYPES = Map.of("jpg", "image/jpeg", "png", "image/png",
            "webp", "image/webp");

    public Evidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(photoUrl, "photoUrl");
        Objects.requireNonNull(uploadedAt, "uploadedAt");
        if (photoUrl.length() > MAX_PHOTO_URL) {
            throw new IllegalArgumentException("photoUrl supera los " + MAX_PHOTO_URL + " caracteres");
        }
    }

    /** Tipo MIME de la foto según su extensión ({@code application/octet-stream} si no se reconoce). */
    public String contentType() {
        int dot = photoUrl.lastIndexOf('.');
        String extension = dot < 0 ? "" : photoUrl.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    /** Nombre con el que se descarga: el id de la evidencia y su extensión. */
    public String fileName() {
        int slash = photoUrl.lastIndexOf('/');
        return photoUrl.substring(slash + 1);
    }
}
