package pe.buildshield.discrepancy.application;

import java.util.Optional;

/**
 * Puerto del almacenamiento de las fotos de evidencia. En la arquitectura v4 lo implementa Amazon S3; en local y
 * en pruebas, una carpeta del disco. El dominio guarda solo la clave ({@code photo_url}).
 */
public interface EvidenceStorage {

    /** @return la clave con la que se vuelve a leer el archivo */
    String store(String key, String contentType, byte[] content);

    Optional<byte[]> load(String key);
}
