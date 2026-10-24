package pe.buildshield.discrepancy.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.discrepancy.application.DiscrepanciesCommandService;
import pe.buildshield.discrepancy.application.DiscrepanciesCommandService.ConfirmedLine;
import pe.buildshield.discrepancy.application.DiscrepanciesCommandService.ConfirmedReception;
import pe.buildshield.discrepancy.shared.messaging.EventEnvelope;
import pe.buildshield.discrepancy.shared.messaging.IdempotentEventProcessor;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static pe.buildshield.discrepancy.infrastructure.messaging.Payloads.decimal;
import static pe.buildshield.discrepancy.infrastructure.messaging.Payloads.text;
import static pe.buildshield.discrepancy.infrastructure.messaging.Payloads.uuid;

/**
 * Reception Event Consumer (C4 v4): consume {@code buildshield.ordering} y, con {@code ReceptionConfirmed}, abre una
 * discrepancia por cada línea fuera de tolerancia (faltante, sobrante o merma). El evento trae todo lo necesario: no
 * se llama a ordering ni a organization. Los demás eventos y los campos desconocidos se ignoran.
 */
@Component
class ReceptionEventConsumer {

    static final String NAME = "ReceptionEventConsumer";
    static final String RECEPTION_CONFIRMED = "ReceptionConfirmed";

    private static final Logger log = LoggerFactory.getLogger(ReceptionEventConsumer.class);

    private final IdempotentEventProcessor processor;
    private final DiscrepanciesCommandService commands;

    ReceptionEventConsumer(IdempotentEventProcessor processor, DiscrepanciesCommandService commands) {
        this.processor = processor;
        this.commands = commands;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.ordering:buildshield.ordering}")
    void onMessage(ConsumerRecord<String, String> record) {
        processor.process(NAME, record, this::handle);
    }

    /** @return cuántas discrepancias se abrieron */
    int handle(EventEnvelope event) {
        if (!RECEPTION_CONFIRMED.equals(event.eventType())) {
            log.debug("Evento {} ignorado por discrepancy", event.eventType());
            return 0;
        }
        JsonNode payload = Payloads.of(event);
        UUID receptionId = uuid(payload, "receptionId");
        UUID dispatchId = uuid(payload, "dispatchId");
        UUID worksiteId = uuid(payload, "worksiteId");
        UUID warehouseId = uuid(payload, "warehouseId");
        if (receptionId == null || dispatchId == null || worksiteId == null || warehouseId == null) {
            log.warn("ReceptionConfirmed sin receptionId, dispatchId, worksiteId o warehouseId: se ignora");
            return 0;
        }
        List<ConfirmedLine> lines = new ArrayList<>();
        payload.path("lines").forEach(line -> lines.add(line(line)));
        UUID confirmedBy = uuid(payload, "confirmedBy");
        if (confirmedBy == null) {
            confirmedBy = event.actorId() == null ? TenantContext.SYSTEM_USER_ID : event.actorId();
        }
        return commands.openFromReception(new ConfirmedReception(receptionId, dispatchId,
                text(payload, "manifestCode"), worksiteId, warehouseId, lines), confirmedBy);
    }

    /** Lee una línea del payload; tolera campos ausentes. */
    static ConfirmedLine line(JsonNode line) {
        BigDecimal dispatched = decimal(line, "dispatched");
        BigDecimal received = decimal(line, "received");
        BigDecimal shrinkage = decimal(line, "shrinkagePercent");
        BigDecimal tolerance = decimal(line, "tolerancePercent");
        boolean within;
        if (line.hasNonNull("withinTolerance")) {
            within = line.get("withinTolerance").asBoolean(true);
        } else {
            within = shrinkage == null || tolerance == null || shrinkage.compareTo(tolerance) <= 0;
        }
        return new ConfirmedLine(uuid(line, "receptionLineId"), uuid(line, "materialId"), text(line, "sku"),
                text(line, "materialName"), text(line, "unit"), decimal(line, "requested"),
                dispatched == null ? BigDecimal.ZERO : dispatched, received == null ? BigDecimal.ZERO : received,
                shrinkage, tolerance, within);
    }
}
