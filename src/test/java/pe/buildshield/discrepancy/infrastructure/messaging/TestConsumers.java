package pe.buildshield.discrepancy.infrastructure.messaging;

import pe.buildshield.discrepancy.application.DiscrepanciesCommandService;
import pe.buildshield.discrepancy.domain.model.StaffAssignments;
import pe.buildshield.discrepancy.shared.messaging.EventEnvelope;

/** Aplica un evento con el consumidor que corresponde a su tipo, sin Kafka ni {@code processed_events}. */
public final class TestConsumers {

    private final ReceptionEventConsumer receptions;
    private final AssignmentEventConsumer assignments;

    public TestConsumers(DiscrepanciesCommandService commands, StaffAssignments staff) {
        this.receptions = new ReceptionEventConsumer(null, commands);
        this.assignments = new AssignmentEventConsumer(null, staff);
    }

    /** @return cuántas discrepancias se abrieron */
    public int handle(EventEnvelope event) {
        if (event.eventType().startsWith("Staff")) {
            assignments.handle(event);
            return 0;
        }
        return receptions.handle(event);
    }
}
