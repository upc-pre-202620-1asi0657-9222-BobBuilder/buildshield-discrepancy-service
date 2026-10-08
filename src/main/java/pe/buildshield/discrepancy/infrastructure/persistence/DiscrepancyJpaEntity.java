package pe.buildshield.discrepancy.infrastructure.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyOrigin;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.domain.model.Note;
import pe.buildshield.discrepancy.shared.persistence.AuditableAbstractAggregateRoot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "discrepancies")
public class DiscrepancyJpaEntity extends AuditableAbstractAggregateRoot<DiscrepancyJpaEntity> {

    @Column(name = "reception_id", nullable = false, updatable = false)
    private UUID receptionId;

    @Column(name = "reception_line_id", updatable = false)
    private UUID receptionLineId;

    @Column(name = "dispatch_id", nullable = false, updatable = false)
    private UUID dispatchId;

    @Column(name = "manifest_code", nullable = false, length = 40, updatable = false)
    private String manifestCode;

    @Column(name = "worksite_id", nullable = false, updatable = false)
    private UUID worksiteId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "sku", nullable = false, length = 40, updatable = false)
    private String sku;

    @Column(name = "material_name", nullable = false, length = 150, updatable = false)
    private String materialName;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private DiscrepancyType type;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal quantity;

    @Column(name = "shrinkage_pct", precision = 7, scale = 2, updatable = false)
    private BigDecimal shrinkagePercent;

    @Column(name = "tolerance_pct", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal tolerancePercent;

    @Column(name = "observation", nullable = false, length = 500, updatable = false)
    private String observation;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 10, updatable = false)
    private DiscrepancyOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private DiscrepancyStatus status;

    @Column(name = "opened_by", nullable = false, updatable = false)
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "response_text", length = 500)
    private String responseText;

    @Column(name = "responded_by")
    private UUID respondedBy;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "escalation_reason", length = 500)
    private String escalationReason;

    @Column(name = "escalated_by")
    private UUID escalatedBy;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "resolution", length = 500)
    private String resolution;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "discrepancy_id", nullable = false, updatable = false)
    @OrderBy("uploadedAt ASC")
    private List<EvidenceJpaEntity> evidences = new ArrayList<>();

    protected DiscrepancyJpaEntity() {
    }

    DiscrepancyJpaEntity(Discrepancy discrepancy) {
        var reception = discrepancy.reception();
        receptionId = reception.receptionId();
        receptionLineId = reception.receptionLineId();
        dispatchId = reception.dispatchId();
        manifestCode = reception.manifestCode();
        worksiteId = reception.worksiteId();
        warehouseId = reception.warehouseId();
        materialId = discrepancy.material().materialId();
        sku = discrepancy.material().sku();
        materialName = discrepancy.material().name();
        type = discrepancy.type();
        quantity = discrepancy.quantity();
        shrinkagePercent = discrepancy.shrinkagePercent();
        tolerancePercent = discrepancy.tolerancePercent();
        observation = discrepancy.observation();
        origin = discrepancy.origin();
        openedBy = discrepancy.openedBy();
        openedAt = discrepancy.openedAt();
        apply(discrepancy);
    }

    void apply(Discrepancy discrepancy) {
        status = discrepancy.status();
        Note response = discrepancy.response();
        responseText = response == null ? null : response.text();
        respondedBy = response == null ? null : response.by();
        respondedAt = response == null ? null : response.at();
        Note escalation = discrepancy.escalation();
        escalationReason = escalation == null ? null : escalation.text();
        escalatedBy = escalation == null ? null : escalation.by();
        escalatedAt = escalation == null ? null : escalation.at();
        Note decision = discrepancy.resolution();
        resolution = decision == null ? null : decision.text();
        resolvedBy = decision == null ? null : decision.by();
        resolvedAt = decision == null ? null : decision.at();
        Note acceptance = discrepancy.acceptance();
        acceptedBy = acceptance == null ? null : acceptance.by();
        acceptedAt = acceptance == null ? null : acceptance.at();
        Set<UUID> known = evidences.stream().map(EvidenceJpaEntity::getId).collect(Collectors.toSet());
        for (Evidence evidence : discrepancy.evidences()) {
            if (!known.contains(evidence.id())) {
                evidences.add(new EvidenceJpaEntity(evidence));
            }
        }
    }

    Discrepancy toDomain() {
        return Discrepancy.restore(getId(),
                new Discrepancy.ReceptionLink(receptionId, receptionLineId, dispatchId, manifestCode, worksiteId,
                        warehouseId),
                new Discrepancy.MaterialLink(materialId, sku, materialName), type, quantity, shrinkagePercent,
                tolerancePercent, observation, origin, status, openedBy, openedAt,
                note(responseText, respondedBy, respondedAt), note(escalationReason, escalatedBy, escalatedAt),
                note(resolution, resolvedBy, resolvedAt), note(acceptedBy == null ? null : "Aceptada", acceptedBy,
                        acceptedAt),
                evidences.stream().map(EvidenceJpaEntity::toDomain).toList(), getVersion());
    }

    private static Note note(String text, UUID by, Instant at) {
        return text == null || by == null || at == null ? null : new Note(text, by, at);
    }
}
