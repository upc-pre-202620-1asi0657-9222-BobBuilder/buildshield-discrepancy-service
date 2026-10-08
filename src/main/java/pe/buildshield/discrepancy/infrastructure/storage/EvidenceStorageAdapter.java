package pe.buildshield.discrepancy.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pe.buildshield.discrepancy.application.EvidenceStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Adaptador de {@link EvidenceStorage} sobre una carpeta local (desarrollo y pruebas). En la nube lo reemplaza el
 * adaptador de Amazon S3 con la misma clave de objeto ({@code organización/discrepancia/evidencia.ext}).
 */
@Component
class EvidenceStorageAdapter implements EvidenceStorage {

    private final Path root;

    EvidenceStorageAdapter(@Value("${buildshield.evidence.local-dir:data/evidence}") String localDir) {
        this.root = Path.of(localDir).toAbsolutePath().normalize();
    }

    @Override
    public String store(String key, String contentType, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return key;
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo guardar la evidencia", ex);
        }
    }

    @Override
    public Optional<byte[]> load(String key) {
        Path source = resolve(key);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(source));
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo leer la evidencia", ex);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Clave de evidencia fuera de la carpeta de evidencias");
        }
        return path;
    }
}
