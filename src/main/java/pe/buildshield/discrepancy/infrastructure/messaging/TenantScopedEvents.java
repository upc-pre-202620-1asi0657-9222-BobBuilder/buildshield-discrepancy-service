package pe.buildshield.discrepancy.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import pe.buildshield.discrepancy.shared.messaging.EventEnvelope;
import pe.buildshield.discrepancy.shared.messaging.IdempotentEventProcessor;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;
import pe.buildshield.discrepancy.shared.tenant.TenantInfo;

import java.util.function.Consumer;

/**
 * Procesa un evento con la organización del sobre ya fijada <b>antes</b> de abrir la transacción. Hibernate
 * resuelve el tenant ({@code @TenantId}) al abrir la sesión, y {@link IdempotentEventProcessor} abre la
 * transacción antes de fijar el contexto: sin este paso, las discrepancias abiertas por un consumidor quedarían con
 * un tenant vacío.
 */
final class TenantScopedEvents {

    private TenantScopedEvents() {
    }

    static boolean process(IdempotentEventProcessor processor, ConsumerRecord<String, String> record,
            Consumer<EventEnvelope> handler) {
        EventEnvelope envelope = processor.parse(record.value());
        TenantInfo tenant = new TenantInfo(envelope.organizationId(), TenantContext.SYSTEM_USER_ID,
                IdempotentEventProcessor.SYSTEM_ROLE);
        return TenantContext.callAs(tenant, () -> processor.process(record, handler));
    }
}
