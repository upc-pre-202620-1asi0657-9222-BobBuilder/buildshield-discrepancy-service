package pe.buildshield.discrepancy.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.discrepancy.application.DiscrepancyEventHandler;
import pe.buildshield.discrepancy.shared.messaging.IdempotentEventProcessor;

/** Consume {@code buildshield.organization}: copias de materiales y asignaciones. */
@Component
class OrganizationEventConsumer {

    private final IdempotentEventProcessor processor;
    private final DiscrepancyEventHandler handler;

    OrganizationEventConsumer(IdempotentEventProcessor processor, DiscrepancyEventHandler handler) {
        this.processor = processor;
        this.handler = handler;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.organization:buildshield.organization}")
    void onMessage(ConsumerRecord<String, String> record) {
        processor.process(record, handler::handle);
    }
}
