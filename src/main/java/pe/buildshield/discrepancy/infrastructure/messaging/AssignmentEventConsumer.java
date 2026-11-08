package pe.buildshield.discrepancy.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.discrepancy.domain.model.StaffAssignments;
import pe.buildshield.discrepancy.shared.messaging.EventEnvelope;
import pe.buildshield.discrepancy.shared.messaging.IdempotentEventProcessor;

import java.util.UUID;

import static pe.buildshield.discrepancy.infrastructure.messaging.Payloads.uuid;

/**
 * Consume {@code StaffAssigned} y {@code StaffUnassigned} de {@code buildshield.organization} para saber qué obras y
 * almacenes ve cada encargado ({@code assignment_projection}). No está en el C4 v4: es la desviación documentada en
 * DESVIACIONES.md, porque sin ella habría que consultar a organization por REST.
 */
@Component
class AssignmentEventConsumer {

    static final String NAME = "AssignmentEventConsumer";
    static final String STAFF_ASSIGNED = "StaffAssigned";
    static final String STAFF_UNASSIGNED = "StaffUnassigned";

    private final IdempotentEventProcessor processor;
    private final StaffAssignments assignments;

    AssignmentEventConsumer(IdempotentEventProcessor processor, StaffAssignments assignments) {
        this.processor = processor;
        this.assignments = assignments;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.organization:buildshield.organization}")
    void onMessage(ConsumerRecord<String, String> record) {
        processor.process(NAME, record, this::handle);
    }

    void handle(EventEnvelope event) {
        JsonNode payload = Payloads.of(event);
        switch (event.eventType()) {
            case STAFF_ASSIGNED -> {
                UUID assignmentId = uuid(payload, "assignmentId");
                UUID userId = uuid(payload, "userId");
                UUID locationId = uuid(payload, "locationId");
                if (assignmentId != null && userId != null && locationId != null) {
                    assignments.assign(event.organizationId(), assignmentId, userId, locationId,
                            payload.path("locationType").asText(""));
                }
            }
            case STAFF_UNASSIGNED -> {
                UUID assignmentId = uuid(payload, "assignmentId");
                if (assignmentId != null) {
                    assignments.unassign(assignmentId);
                }
            }
            default -> {
                // Materiales, lugares y demás eventos de organization: discrepancy no los necesita.
            }
        }
    }
}
