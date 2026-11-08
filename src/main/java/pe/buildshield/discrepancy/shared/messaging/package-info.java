/**
 * Eventos de integración entre microservicios: sobre JSON común ({@link pe.buildshield.discrepancy.shared.messaging.EventEnvelope}),
 * Transactional Outbox ({@link pe.buildshield.discrepancy.shared.messaging.OutboxWriter} y
 * {@link pe.buildshield.discrepancy.shared.messaging.OutboxPublisher}) y consumo idempotente
 * ({@link pe.buildshield.discrepancy.shared.messaging.IdempotentEventProcessor}). Entrega al menos una vez.
 */
package pe.buildshield.discrepancy.shared.messaging;
