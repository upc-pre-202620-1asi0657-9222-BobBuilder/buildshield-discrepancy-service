package pe.buildshield.discrepancy.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyRepository;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.domain.model.LogisticsCopies;
import pe.buildshield.discrepancy.domain.model.MaterialSnapshot;
import pe.buildshield.discrepancy.domain.model.ReceptionSnapshot;
import pe.buildshield.discrepancy.shared.error.ConflictException;
import pe.buildshield.discrepancy.shared.error.ErrorDetail;
import pe.buildshield.discrepancy.shared.error.ResourceNotFoundException;
import pe.buildshield.discrepancy.shared.error.ValidationException;
import pe.buildshield.discrepancy.shared.messaging.OutboxWriter;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;
import pe.buildshield.discrepancy.shared.tenant.TenantInfo;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Casos de uso que cambian discrepancias (US36). Cada cambio publica su evento por el outbox en la misma
 * transacción. Quien no ve la discrepancia (otra organización, otra obra u otro almacén) recibe 404.
 */
@Service
public class DiscrepancyCommandService {

    public static final long MAX_EVIDENCE_BYTES = 5L * 1024 * 1024;
    static final Map<String, String> EVIDENCE_TYPES = Map.of("image/jpeg", "jpg", "image/png", "png",
            "image/webp", "webp");

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyCommandService.class);

    private final DiscrepancyRepository discrepancies;
    private final LogisticsCopies copies;
    private final DiscrepancyVisibility visibility;
    private final EvidenceStorage storage;
    private final OutboxWriter outbox;
    private final Clock clock;

    public DiscrepancyCommandService(DiscrepancyRepository discrepancies, LogisticsCopies copies,
            DiscrepancyVisibility visibility, EvidenceStorage storage, OutboxWriter outbox, Clock clock) {
        this.discrepancies = discrepancies;
        this.copies = copies;
        this.visibility = visibility;
        this.storage = storage;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Datos de {@code POST /discrepancies}. */
    public record CreateDiscrepancy(UUID receptionId, UUID materialId, DiscrepancyType type, BigDecimal quantity,
            String observation) {
    }

    /** Archivo subido como evidencia. */
    public record EvidenceUpload(String fileName, String contentType, byte[] content) {
    }

    /**
     * Abre una discrepancia por cada línea fuera de tolerancia de la recepción confirmada (QAS10: una sola por línea
     * aunque la confirmación llegue repetida). Corre en el consumidor de {@code ReceptionConfirmed}.
     *
     * @return cuántas abrió
     */
    @Transactional
    public int openFromReception(ReceptionSnapshot reception, List<ConfirmedLine> lines, UUID confirmedBy) {
        UUID organization = TenantContext.require().organizationId();
        int opened = 0;
        for (ConfirmedLine line : lines) {
            if (line.withinTolerance() || line.receptionLineId() == null
                    || discrepancies.existsForReceptionLine(line.receptionLineId())) {
                continue;
            }
            BigDecimal difference = line.received().subtract(line.dispatched()).abs();
            if (difference.signum() == 0) {
                continue;
            }
            MaterialSnapshot known = copies.material(organization, line.materialId()).orElse(null);
            Discrepancy discrepancy = Discrepancy.openAutomatic(reception.link(line.receptionLineId()),
                    new Discrepancy.MaterialLink(line.materialId(), firstNonBlank(line.sku(), known == null ? null
                            : known.sku()), firstNonBlank(line.materialName(), known == null ? null : known.name())),
                    line.dispatched(), line.received(), line.shrinkagePercent(),
                    line.tolerancePercent() != null ? line.tolerancePercent()
                            : known == null ? null : known.tolerancePercent(),
                    confirmedBy, clock.instant());
            if (discrepancies.existsUnclosed(reception.receptionId(), line.materialId(), discrepancy.type())) {
                log.info("Ya hay una discrepancia abierta de {} en la recepción {}", line.materialId(),
                        reception.receptionId());
                continue;
            }
            Discrepancy saved = discrepancies.save(discrepancy);
            publish(DiscrepancyEvents.OPENED, saved, saved.observation(), confirmedBy);
            opened++;
        }
        return opened;
    }

    /** Línea de {@code ReceptionConfirmed} tal como llega en el evento. */
    public record ConfirmedLine(UUID receptionLineId, UUID materialId, String sku, String materialName,
            BigDecimal requested, BigDecimal dispatched, BigDecimal received, BigDecimal shrinkagePercent,
            BigDecimal tolerancePercent, boolean withinTolerance) {
    }

    /** El encargado de obra registra otra diferencia de una recepción de su obra. */
    @Transactional
    public Discrepancy create(CreateDiscrepancy command) {
        TenantInfo requester = TenantContext.require();
        if (command.receptionId() == null || command.materialId() == null) {
            throw new ValidationException("INVALID_DISCREPANCY", "La recepción y el material son obligatorios",
                    List.of(new ErrorDetail(command.receptionId() == null ? "receptionId" : "materialId",
                            "obligatorio")));
        }
        ReceptionSnapshot reception = copies.reception(requester.organizationId(), command.receptionId())
                .filter(found -> visibility.canSeeWorksiteOrWarehouse(found.worksiteId(), found.warehouseId()))
                .orElseThrow(() -> new ResourceNotFoundException("RECEPTION_NOT_FOUND",
                        "La recepción " + command.receptionId() + " no existe"));
        ReceptionSnapshot.Line line = reception.line(command.materialId()).orElse(null);
        MaterialSnapshot material = copies.material(requester.organizationId(), command.materialId()).orElse(null);
        if (line == null && material == null) {
            throw new ResourceNotFoundException("MATERIAL_NOT_FOUND", "El material " + command.materialId()
                    + " no existe");
        }
        Discrepancy discrepancy = Discrepancy.openManual(reception.link(null),
                new Discrepancy.MaterialLink(command.materialId(),
                        firstNonBlank(line == null ? null : line.sku(), material == null ? null : material.sku()),
                        firstNonBlank(line == null ? null : line.materialName(), material == null ? null
                                : material.name())),
                command.type(), command.quantity(), line == null ? null : line.shrinkagePercent(),
                line != null && line.tolerancePercent() != null ? line.tolerancePercent()
                        : material == null ? null : material.tolerancePercent(),
                command.observation(), requester.userId(), clock.instant());
        if (discrepancies.existsUnclosed(command.receptionId(), command.materialId(), command.type())) {
            throw alreadyOpen();
        }
        try {
            Discrepancy saved = discrepancies.save(discrepancy);
            publish(DiscrepancyEvents.OPENED, saved, saved.observation(), requester.userId());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            throw alreadyOpen();
        }
    }

    @Transactional
    public Discrepancy respond(UUID id, String text) {
        Discrepancy discrepancy = visible(id);
        discrepancy.respond(text, requester(), clock.instant());
        return saveAndPublish(DiscrepancyEvents.RESPONDED, discrepancy, discrepancy.response().text());
    }

    @Transactional
    public Discrepancy accept(UUID id) {
        Discrepancy discrepancy = visible(id);
        discrepancy.accept(requester(), clock.instant());
        return saveAndPublish(DiscrepancyEvents.ACCEPTED, discrepancy, discrepancy.response().text());
    }

    @Transactional
    public Discrepancy escalate(UUID id, String reason) {
        Discrepancy discrepancy = visible(id);
        discrepancy.escalate(reason, requester(), clock.instant());
        return saveAndPublish(DiscrepancyEvents.ESCALATED, discrepancy, discrepancy.escalation().text());
    }

    @Transactional
    public Discrepancy resolve(UUID id, String decision) {
        Discrepancy discrepancy = visible(id);
        discrepancy.resolve(decision, requester(), clock.instant());
        return saveAndPublish(DiscrepancyEvents.RESOLVED, discrepancy, discrepancy.resolution().text());
    }

    /** Foto JPG, PNG o WEBP de hasta 5 MB; se verifica también el contenido, no solo el tipo declarado. */
    @Transactional
    public Evidence addEvidence(UUID id, EvidenceUpload upload) {
        Discrepancy discrepancy = visible(id);
        byte[] content = upload.content() == null ? new byte[0] : upload.content();
        if (content.length == 0) {
            throw new ValidationException("EVIDENCE_EMPTY", "El archivo está vacío",
                    List.of(new ErrorDetail("file", "obligatorio")));
        }
        if (content.length > MAX_EVIDENCE_BYTES) {
            throw new ValidationException("EVIDENCE_TOO_LARGE", "La foto supera los 5 MB",
                    List.of(new ErrorDetail("file", "hasta 5 MB")));
        }
        String contentType = upload.contentType() == null ? "" : upload.contentType().toLowerCase(Locale.ROOT).trim();
        String extension = EVIDENCE_TYPES.get(contentType);
        if (extension == null || !ImageSignatures.matches(contentType, content)) {
            throw new ValidationException("EVIDENCE_TYPE_NOT_ALLOWED", "La evidencia debe ser una imagen JPG, PNG o WEBP",
                    List.of(new ErrorDetail("file", "image/jpeg, image/png o image/webp")));
        }
        UUID evidenceId = UUID.randomUUID();
        UUID organization = TenantContext.require().organizationId();
        String key = storage.store(organization + "/" + discrepancy.id() + "/" + evidenceId + "." + extension,
                contentType, content);
        Evidence evidence = new Evidence(evidenceId, key, "/api/v1/discrepancies/" + discrepancy.id() + "/evidences/"
                + evidenceId + "/content", fileName(upload.fileName(), extension), contentType, content.length,
                requester(), clock.instant());
        discrepancy.addEvidence(evidence);
        discrepancies.save(discrepancy);
        return evidence;
    }

    private Discrepancy visible(UUID id) {
        return discrepancies.findById(id).filter(visibility::canSee).orElseThrow(() ->
                new ResourceNotFoundException("DISCREPANCY_NOT_FOUND", "La discrepancia " + id + " no existe"));
    }

    private Discrepancy saveAndPublish(String eventType, Discrepancy discrepancy, String text) {
        Discrepancy saved = discrepancies.save(discrepancy);
        publish(eventType, saved, text, requester());
        return saved;
    }

    private void publish(String eventType, Discrepancy discrepancy, String text, UUID actor) {
        UUID organization = TenantContext.require().organizationId();
        outbox.append(organization, TenantContext.SYSTEM_USER_ID.equals(actor) ? null : actor, eventType,
                DiscrepancyEvents.DISCREPANCY, discrepancy.id(),
                DiscrepancyEvents.DiscrepancyChanged.of(discrepancy, text));
    }

    private static UUID requester() {
        return TenantContext.require().userId();
    }

    private static ConflictException alreadyOpen() {
        return new ConflictException("DISCREPANCY_ALREADY_OPEN",
                "Ya hay una discrepancia abierta de ese tipo para el material en esta recepción");
    }

    private static String fileName(String original, String extension) {
        String name = original == null ? "" : original.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isBlank()) {
            name = "evidencia." + extension;
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    /** Lee una línea del payload de {@code ReceptionConfirmed}; tolera campos ausentes. */
    public static ConfirmedLine line(JsonNode line) {
        BigDecimal dispatched = decimal(line, "dispatched");
        BigDecimal received = decimal(line, "received");
        BigDecimal shrinkage = decimal(line, "shrinkagePercent");
        BigDecimal tolerance = decimal(line, "tolerancePercent");
        boolean within;
        if (line.has("withinTolerance") && !line.get("withinTolerance").isNull()) {
            within = line.get("withinTolerance").asBoolean(true);
        } else {
            within = shrinkage == null || tolerance == null || shrinkage.compareTo(tolerance) <= 0;
        }
        return new ConfirmedLine(uuid(line, "receptionLineId"), uuid(line, "materialId"), text(line, "sku"),
                text(line, "materialName"), decimal(line, "requested"), dispatched == null ? BigDecimal.ZERO
                : dispatched, received == null ? BigDecimal.ZERO : received, shrinkage, tolerance, within);
    }

    static UUID uuid(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        try {
            return UUID.fromString(node.get(field).asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static String text(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.get(field).asText().isBlank() ? node.get(field).asText() : null;
    }

    static BigDecimal decimal(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        try {
            return new BigDecimal(node.get(field).asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
