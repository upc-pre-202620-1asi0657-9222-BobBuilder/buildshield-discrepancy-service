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

    @Column(name = "photo_url", nullable = false, length = 255, updatable = false)
    private String photoUrl;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected EvidenceJpaEntity() {
    }

    EvidenceJpaEntity(Evidence evidence) {
        id = evidence.id();
        photoUrl = evidence.photoUrl();
        uploadedAt = evidence.uploadedAt();
    }

    UUID getId() {
        return id;
    }

    Evidence toDomain() {
        return new Evidence(id, photoUrl, uploadedAt);
    }
}
