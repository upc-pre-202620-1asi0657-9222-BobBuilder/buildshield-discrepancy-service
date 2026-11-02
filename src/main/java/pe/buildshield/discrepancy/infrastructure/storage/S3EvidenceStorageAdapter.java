package pe.buildshield.discrepancy.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pe.buildshield.discrepancy.application.EvidenceStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.Optional;

/**
 * Adaptador de {@link EvidenceStorage} sobre un bucket privado de Amazon S3 (perfil prod). Usa la misma clave de objeto
 * que la carpeta local. Las fotos no se exponen desde S3: se leen a través del servicio con la URL firmada de
 * {@code EvidenceUrlSigner}, que ya comprueba el acceso de quien la pidió.
 */
@Component
@ConditionalOnProperty(name = "buildshield.evidence.storage", havingValue = "s3")
class S3EvidenceStorageAdapter implements EvidenceStorage {

    private final S3Client s3;
    private final String bucket;

    S3EvidenceStorageAdapter(S3Client s3, @Value("${buildshield.evidence.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public String store(String key, String contentType, byte[] content) {
        s3.putObject(request -> request.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
        return key;
    }

    @Override
    public Optional<byte[]> load(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray());
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        }
    }
}
