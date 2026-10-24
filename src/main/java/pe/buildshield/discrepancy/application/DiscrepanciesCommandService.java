package pe.buildshield.discrepancy.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.discrepancy.domain.model.DiscrepanciesRepository;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.shared.error.ErrorDetail;
import pe.buildshield.discrepancy.shared.error.ResourceNotFoundException;
import pe.buildshield.discrepancy.shared.error.ValidationException;
import pe.buildshield.discrepancy.shared.messaging.OutboxWriter;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Discrepancies CommandService (C4 v4): registrar, responder, escalar y resolver discrepancias (US36). Cada cambio
 * publica su evento por el outbox en la misma transacción. Quien no ve la discrepancia (otra organización, otra obra u
 * otro almacén) recibe 404.
 */
@Service
public class DiscrepanciesCommandService {

    public static final long MAX_EVIDENCE_BYTES = 5L * 1024 * 1024;
    static final Map<String, String> EVIDENCE_TYPES = Map.of("image/jpeg", "jpg", "image/png", "png",
            "image/webp", "webp");

    private static final Logger log = LoggerFactory.getLogger(DiscrepanciesCommandService.class);

    private final DiscrepanciesRepository discrepancies;
    private final DiscrepancyVisibility visibility;
    private final EvidenceStorage storage;
    private final OutboxWriter outbox;
    private final Clock clock;

    public DiscrepanciesCommandService(DiscrepanciesRepository discrepancies, DiscrepancyVisibility visibility,
            EvidenceStorage storage, OutboxWriter outbox, Clock clock) {
        this.discrepancies = discrepancies;
        this.visibility = visibility;
        this.storage = storage;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Recepción confirmada tal como llega en {@code ReceptionConfirmed}. */
    public record ConfirmedReception(UUID receptionId, UUID dispatchId, String manifestCode, UUID worksiteId,
            UUID warehouseId, List<ConfirmedLine> lines) {
    }

    /** Línea de {@code ReceptionConfirmed}: trae el material y las cantidades, así que no se consulta a nadie. */
    public record ConfirmedLine(UUID receptionLineId, UUID materialId, String sku, String materialName, String unit,
            BigDecimal requested, BigDecimal dispatched, BigDecimal received, BigDecimal shrinkagePercent,
            BigDecimal tolerancePercent, boolean withinTolerance) {
    }

    /** Archivo subido como evidencia. */
    public record EvidenceUpload(String fileName, String contentType, byte[] content) {
    }

    /**
     * Abre una discrepancia por cada línea fuera de tolerancia de la recepción confirmada (QAS10: una sola por línea
     * aunque la confirmación llegue repetida). Corre en {@code ReceptionEventConsumer}.
     *
     * @return cuántas abrió
     */
    @Transactional
    public int openFromReception(ConfirmedReception reception, UUID confirmedBy) {
        int opened = 0;
        for (ConfirmedLine line : reception.lines()) {
            if (line.withinTolerance() || line.receptionLineId() == null || line.materialId() == null
                    || discrepancies.existsForReceptionLine(line.receptionLineId())) {
                continue;
            }
            if (line.received().compareTo(line.dispatched()) == 0) {
                continue;
            }
            Discrepancy discrepancy = Discrepancy.open(
                    new Discrepancy.ReceptionLink(reception.receptionId(), line.receptionLineId(),
                            reception.dispatchId(), reception.manifestCode(), reception.worksiteId(),
                            reception.warehouseId()),
                    new Discrepancy.MaterialLink(line.materialId(), line.sku(), line.materialName(), line.unit()),
                    line.dispatched(), line.received(), line.shrinkagePercent(), line.tolerancePercent(), confirmedBy,
                    clock.instant());
            Discrepancy saved = discrepancies.save(discrepancy);
            publish(DiscrepancyEvents.OPENED, saved, saved.reason(), confirmedBy);
            opened++;
        }
        if (opened > 0) {
            log.info("Recepción {}: {} discrepancias abiertas", reception.receptionId(), opened);
        }
        return opened;
    }

    @Transactional
    public Discrepancy respond(UUID id, String text) {
        Discrepancy discrepancy = visible(id);
        discrepancy.respond(text, requester(), clock.instant());
        return saveAndPublish(DiscrepancyEvents.RESPONDED, discrepancy,
                discrepancy.response().orElseThrow().comment());
    }

    @Transactional
    public Discrepancy accept(UUID id) {
        Discrepancy discrepancy = visible(id);
        discrepancy.accept(requester(), clock.instant());
        return saveAndPublish(DiscrepancyEvents.ACCEPTED, discrepancy,
                discrepancy.response().orElseThrow().comment());
    }

    @Transactional
    public Discrepancy escalate(UUID id, String reason) {
        Discrepancy discrepancy = visible(id);
        discrepancy.escalate(reason, requester(), clock.instant());
        return saveAndPublish(DiscrepancyEvents.ESCALATED, discrepancy,
                discrepancy.escalation().orElseThrow().comment());
    }

    @Transactional
    public Discrepancy resolve(UUID id, String decision) {
        Discrepancy discrepancy = visible(id);
        discrepancy.resolve(decision, requester(), clock.instant());
        return saveAndPublish(DiscrepancyEvents.RESOLVED, discrepancy,
                discrepancy.resolution().orElseThrow().decision());
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
        Evidence evidence = new Evidence(evidenceId, key, clock.instant());
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
                discrepancy.id(), DiscrepancyEvents.DiscrepancyChanged.of(discrepancy, text));
    }

    private static UUID requester() {
        return TenantContext.require().userId();
    }
}
