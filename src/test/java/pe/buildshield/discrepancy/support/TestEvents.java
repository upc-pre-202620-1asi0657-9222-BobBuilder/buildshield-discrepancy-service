package pe.buildshield.discrepancy.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sobres JSON de eventos de organization y ordering, con los campos de {@code notas-etapa-b1.md} (incluidos los
 * adicionales que discrepancy debe tolerar).
 */
public final class TestEvents {

    private final ObjectMapper json;
    private final UUID organization;

    public TestEvents(ObjectMapper json, UUID organization) {
        this.json = json;
        this.organization = organization;
    }

    public String envelope(UUID eventId, String eventType, String aggregateType, Object aggregateId, UUID actorId,
            Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("organizationId", organization);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("actorId", actorId);
        envelope.put("version", 1);
        envelope.put("payload", payload);
        try {
            return json.writeValueAsString(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String material(UUID materialId, String sku, String name, String tolerance) {
        return envelope(UUID.randomUUID(), "MaterialRegistered", "MATERIAL", materialId, null, map("materialId",
                materialId, "sku", sku, "name", name, "unit", "BAG", "tolerancePercent", new BigDecimal(tolerance),
                "active", true));
    }

    public String assigned(UUID assignmentId, UUID userId, UUID locationId, String locationType) {
        return envelope(UUID.randomUUID(), "StaffAssigned", "ASSIGNMENT", assignmentId, null, map("assignmentId",
                assignmentId, "userId", userId, "locationId", locationId, "locationType", locationType));
    }

    public String unassigned(UUID assignmentId, UUID userId, UUID locationId, String locationType) {
        return envelope(UUID.randomUUID(), "StaffUnassigned", "ASSIGNMENT", assignmentId, null, map("assignmentId",
                assignmentId, "userId", userId, "locationId", locationId, "locationType", locationType));
    }

    public String receptionOpened(Reception reception, UUID actor) {
        return envelope(UUID.randomUUID(), "ReceptionOpened", "RECEPTION", reception.receptionId(), actor,
                map("receptionId", reception.receptionId(), "dispatchId", reception.dispatchId(), "orderId",
                        UUID.randomUUID(), "manifestCode", reception.manifestCode(), "warehouseId",
                        reception.warehouseId(), "worksiteId", reception.worksiteId()));
    }

    /** {@code ReceptionConfirmed} con un eventId dado (para repetir el mismo evento o mandar otro igual). */
    public String receptionConfirmed(UUID eventId, Reception reception, UUID confirmedBy, List<Line> lines) {
        List<Map<String, Object>> payloadLines = new ArrayList<>();
        for (Line line : lines) {
            BigDecimal shrinkage = line.dispatched().subtract(line.received()).abs().multiply(BigDecimal.valueOf(100))
                    .divide(line.dispatched(), 2, java.math.RoundingMode.HALF_UP);
            payloadLines.add(map("receptionLineId", line.receptionLineId(), "dispatchLineId", UUID.randomUUID(),
                    "orderLineId", UUID.randomUUID(), "materialId", line.materialId(), "sku", line.sku(),
                    "materialName", line.name(), "unit", "BAG", "requested", line.dispatched(), "dispatched",
                    line.dispatched(), "received", line.received(), "deltaQuantity", line.received(),
                    "shrinkagePercent", shrinkage, "tolerancePercent", line.tolerance(), "withinTolerance",
                    shrinkage.compareTo(line.tolerance()) <= 0));
        }
        return envelope(eventId, "ReceptionConfirmed", "RECEPTION", reception.receptionId(), confirmedBy,
                map("receptionId", reception.receptionId(), "dispatchId", reception.dispatchId(), "orderId",
                        UUID.randomUUID(), "manifestCode", reception.manifestCode(), "worksiteId",
                        reception.worksiteId(), "warehouseId", reception.warehouseId(), "confirmedBy", confirmedBy,
                        "confirmedAt", Instant.now().toString(), "lines", payloadLines, "campoNuevo", "se ignora"));
    }

    public record Reception(UUID receptionId, UUID dispatchId, String manifestCode, UUID worksiteId,
            UUID warehouseId) {

        public static Reception random(String manifest, UUID worksiteId, UUID warehouseId) {
            return new Reception(UUID.randomUUID(), UUID.randomUUID(), manifest, worksiteId, warehouseId);
        }
    }

    public record Line(UUID receptionLineId, UUID materialId, String sku, String name, BigDecimal dispatched,
            BigDecimal received, BigDecimal tolerance) {

        public static Line of(UUID materialId, String sku, String name, String dispatched, String received,
                String tolerance) {
            return new Line(UUID.randomUUID(), materialId, sku, name, new BigDecimal(dispatched),
                    new BigDecimal(received), new BigDecimal(tolerance));
        }
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
