package pe.buildshield.discrepancy.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pe.buildshield.discrepancy.domain.model.Response;
import pe.buildshield.discrepancy.shared.persistence.OrganizationScopedEntity;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "discrepancy_responses")
public class ResponseJpaEntity extends OrganizationScopedEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 15, updatable = false)
    private Response.Decision decision;

    @Column(name = "comment", nullable = false, length = 500, updatable = false)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ResponseJpaEntity() {
    }

    ResponseJpaEntity(Response response) {
        id = response.id();
        authorId = response.authorId();
        decision = response.decision();
        comment = response.comment();
        createdAt = response.createdAt();
    }

    UUID getId() {
        return id;
    }

    Response toDomain() {
        return new Response(id, decision, authorId, comment, createdAt);
    }
}
