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
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.domain.model.Response;
import pe.buildshield.discrepancy.shared.persistence.AuditableAbstractAggregateRoot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tabla {@code discrepancies}. Quién y cuándo la abrió son {@code created_by} y {@code created_at} de la auditoría.
 * Las columnas fuera del ER v4 (recepción, despacho, lugares y material) están en DESVIACIONES.md.
 */
@Entity
@Table(name = "discrepancies")
public class DiscrepancyJpaEntity extends AuditableAbstractAggregateRoot<DiscrepancyJpaEntity> {

    @Column(name = "reception_id", nullable = false, updatable = false)
    private UUID receptionId;

    @Column(name = "reception_line_id", nullable = false, unique = true, updatable = false)
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

    @Column(name = "unit", length = 10, updatable = false)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private DiscrepancyType type;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal quantity;

    @Column(name = "reason", nullable = false, length = 60, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private DiscrepancyStatus status;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    // Set y no List: Hibernate no carga dos colecciones List EAGER a la vez.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "discrepancy_id", nullable = false, updatable = false)
    @OrderBy("createdAt ASC")
    private Set<ResponseJpaEntity> responses = new LinkedHashSet<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "discrepancy_id", nullable = false, updatable = false)
    @OrderBy("uploadedAt ASC")
    private Set<EvidenceJpaEntity> evidences = new LinkedHashSet<>();

    protected DiscrepancyJpaEntity() {
    }

    DiscrepancyJpaEntity(Discrepancy discrepancy) {
        var reception = discrepancy.reception();
        var material = discrepancy.material();
        receptionId = reception.receptionId();
        receptionLineId = reception.receptionLineId();
        dispatchId = reception.dispatchId();
        manifestCode = reception.manifestCode();
        worksiteId = reception.worksiteId();
        warehouseId = reception.warehouseId();
        materialId = material.materialId();
        sku = material.sku();
        materialName = material.name();
        unit = material.unit();
        type = discrepancy.type();
        quantity = discrepancy.quantity();
        reason = discrepancy.reason();
        apply(discrepancy);
    }

    void apply(Discrepancy discrepancy) {
        status = discrepancy.status();
        escalatedAt = discrepancy.escalatedAt();
        resolvedAt = discrepancy.resolvedAt();
        Set<UUID> knownResponses = responses.stream().map(ResponseJpaEntity::getId).collect(Collectors.toSet());
        for (Response response : discrepancy.responses()) {
            if (!knownResponses.contains(response.id())) {
                responses.add(new ResponseJpaEntity(response));
            }
        }
        Set<UUID> knownEvidences = evidences.stream().map(EvidenceJpaEntity::getId).collect(Collectors.toSet());
        for (Evidence evidence : discrepancy.evidences()) {
            if (!knownEvidences.contains(evidence.id())) {
                evidences.add(new EvidenceJpaEntity(evidence));
            }
        }
    }

    Discrepancy toDomain() {
        List<Evidence> photos = new ArrayList<>(evidences.stream().map(EvidenceJpaEntity::toDomain).toList());
        photos.sort(Comparator.comparing(Evidence::uploadedAt));
        return Discrepancy.restore(getId(),
                new Discrepancy.ReceptionLink(receptionId, receptionLineId, dispatchId, manifestCode, worksiteId,
                        warehouseId),
                new Discrepancy.MaterialLink(materialId, sku, materialName, unit), type, quantity, reason, status,
                getCreatedBy(), getCreatedAt(), escalatedAt, resolvedAt,
                responses.stream().map(ResponseJpaEntity::toDomain).toList(), photos, getVersion());
    }
}
