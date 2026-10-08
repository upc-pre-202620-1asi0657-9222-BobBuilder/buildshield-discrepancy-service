package pe.buildshield.discrepancy.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.discrepancy.application.DiscrepancyEventHandler;
import pe.buildshield.discrepancy.shared.messaging.IdempotentEventProcessor;

/**
 * Consume {@code buildshield.ordering}: guarda los datos de cada recepción y, con {@code ReceptionConfirmed}, abre
 * una discrepancia por cada línea fuera de tolerancia.
 */
@Component
class OrderingEventConsumer {

    private final IdempotentEventProcessor processor;
    private final DiscrepancyEventHandler handler;

    OrderingEventConsumer(IdempotentEventProcessor processor, DiscrepancyEventHandler handler) {
        this.processor = processor;
        this.handler = handler;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.ordering:buildshield.ordering}")
    void onMessage(ConsumerRecord<String, String> record) {
        processor.process(record, handler::handle);
    }
}
