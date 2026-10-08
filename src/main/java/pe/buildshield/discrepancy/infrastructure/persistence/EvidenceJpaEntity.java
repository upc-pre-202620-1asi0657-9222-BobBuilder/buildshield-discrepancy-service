package pe.buildshield.discrepancy.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.shared.persistence.OrganizationScopedEntity;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "discrepancy_evidences")
public class EvidenceJpaEntity extends OrganizationScopedEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "storage_key", nullable = false, length = 300, updatable = false)
    private String storageKey;

    @Column(name = "url", nullable = false, length = 500, updatable = false)
    private String url;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 50, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected EvidenceJpaEntity() {
    }

    EvidenceJpaEntity(Evidence evidence) {
        id = evidence.id();
        storageKey = evidence.storageKey();
        url = evidence.url();
        fileName = evidence.fileName();
        contentType = evidence.contentType();
        sizeBytes = evidence.sizeBytes();
        uploadedBy = evidence.uploadedBy();
        uploadedAt = evidence.uploadedAt();
    }

    UUID getId() {
        return id;
    }

    Evidence toDomain() {
        return new Evidence(id, storageKey, url, fileName, contentType, sizeBytes, uploadedBy, uploadedAt);
    }
}
