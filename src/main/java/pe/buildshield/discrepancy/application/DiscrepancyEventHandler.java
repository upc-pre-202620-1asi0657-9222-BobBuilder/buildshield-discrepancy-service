package pe.buildshield.discrepancy.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.discrepancy.domain.model.LogisticsCopies;
import pe.buildshield.discrepancy.domain.model.MaterialSnapshot;
import pe.buildshield.discrepancy.domain.model.ReceptionSnapshot;
import pe.buildshield.discrepancy.shared.messaging.EventEnvelope;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static pe.buildshield.discrepancy.application.DiscrepancyCommandService.decimal;
import static pe.buildshield.discrepancy.application.DiscrepancyCommandService.text;
import static pe.buildshield.discrepancy.application.DiscrepancyCommandService.uuid;

/**
 * Aplica los eventos de organization y ordering: actualiza las copias locales (materiales, asignaciones,
 * recepciones) y, con {@code ReceptionConfirmed}, abre las discrepancias de las líneas fuera de tolerancia. Los
 * tipos de evento desconocidos y los campos adicionales se ignoran.
 */
@Service
public class DiscrepancyEventHandler {

    static final String MATERIAL_REGISTERED = "MaterialRegistered";
    static final String MATERIAL_UPDATED = "MaterialUpdated";
    static final String STAFF_ASSIGNED = "StaffAssigned";
    static final String STAFF_UNASSIGNED = "StaffUnassigned";
    static final String RECEPTION_OPENED = "ReceptionOpened";
    static final String RECEPTION_PARTIALLY_RECEIVED = "ReceptionPartiallyReceived";
    static final String RECEPTION_CONFIRMED = "ReceptionConfirmed";

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyEventHandler.class);

    private final LogisticsCopies copies;
    private final DiscrepancyCommandService commands;

    public DiscrepancyEventHandler(LogisticsCopies copies, DiscrepancyCommandService commands) {
        this.copies = copies;
        this.commands = commands;
    }

    /** @return cuántas discrepancias se abrieron */
    @Transactional
    public int handle(EventEnvelope event) {
        UUID organization = event.organizationId();
        JsonNode payload = event.payload() == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                : event.payload();
        switch (event.eventType()) {
            case MATERIAL_REGISTERED, MATERIAL_UPDATED -> {
                UUID materialId = uuid(payload, "materialId");
                if (materialId != null && text(payload, "sku") != null && text(payload, "name") != null) {
                    BigDecimal tolerance = decimal(payload, "tolerancePercent");
                    copies.upsertMaterial(organization, new MaterialSnapshot(materialId, text(payload, "sku"),
                            text(payload, "name"), tolerance == null ? BigDecimal.ZERO : tolerance),
                            payload.path("active").asBoolean(true));
                }
            }
            case STAFF_ASSIGNED -> {
                UUID assignmentId = uuid(payload, "assignmentId");
                UUID userId = uuid(payload, "userId");
                UUID locationId = uuid(payload, "locationId");
                if (assignmentId != null && userId != null && locationId != null) {
                    copies.assign(organization, assignmentId, userId, locationId, payload.path("locationType").asText(""));
                }
            }
            case STAFF_UNASSIGNED -> {
                UUID assignmentId = uuid(payload, "assignmentId");
                if (assignmentId != null) {
                    copies.unassign(assignmentId);
                }
            }
            case RECEPTION_OPENED, RECEPTION_PARTIALLY_RECEIVED -> reception(organization, payload, false, List.of());
            case RECEPTION_CONFIRMED -> {
                List<DiscrepancyCommandService.ConfirmedLine> lines = new ArrayList<>();
                payload.path("lines").forEach(line -> lines.add(DiscrepancyCommandService.line(line)));
                ReceptionSnapshot reception = reception(organization, payload, true, lines);
                if (reception == null) {
                    return 0;
                }
                UUID confirmedBy = uuid(payload, "confirmedBy");
                if (confirmedBy == null) {
                    confirmedBy = event.actorId() == null ? TenantContext.SYSTEM_USER_ID : event.actorId();
                }
                int opened = commands.openFromReception(reception, lines, confirmedBy);
                if (opened > 0) {
                    log.info("Recepción {}: {} discrepancias abiertas", reception.receptionId(), opened);
                }
                return opened;
            }
            default -> log.debug("Evento {} ignorado por discrepancy", event.eventType());
        }
        return 0;
    }

    private ReceptionSnapshot reception(UUID organization, JsonNode payload, boolean confirmed,
            List<DiscrepancyCommandService.ConfirmedLine> lines) {
        UUID receptionId = uuid(payload, "receptionId");
        UUID dispatchId = uuid(payload, "dispatchId");
        UUID worksiteId = uuid(payload, "worksiteId");
        UUID warehouseId = uuid(payload, "warehouseId");
        if (receptionId == null || dispatchId == null || worksiteId == null || warehouseId == null) {
            log.warn("Evento de recepción sin receptionId, dispatchId, worksiteId o warehouseId: se ignora");
            return null;
        }
        ReceptionSnapshot snapshot = new ReceptionSnapshot(receptionId, dispatchId, text(payload, "manifestCode"),
                worksiteId, warehouseId, confirmed, lines.stream()
                .filter(line -> line.receptionLineId() != null && line.materialId() != null)
                .map(line -> new ReceptionSnapshot.Line(line.receptionLineId(), line.materialId(), line.sku(),
                        line.materialName(), line.dispatched(), line.received(), line.shrinkagePercent(),
                        line.tolerancePercent()))
                .toList());
        copies.upsertReception(organization, snapshot);
        return copies.reception(organization, receptionId).orElse(snapshot);
    }
}
