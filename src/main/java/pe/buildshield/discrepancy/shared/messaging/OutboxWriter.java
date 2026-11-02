package pe.buildshield.discrepancy.shared.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.discrepancy.shared.correlation.CorrelationId;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;
import pe.buildshield.discrepancy.shared.tenant.TenantInfo;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.UUID;

/**
 * Escribe un evento en la tabla {@code outbox_events} dentro de la transacción del cambio de negocio
 * (Transactional Outbox): si el negocio hace rollback, el evento tampoco existe. Lo envía después
 * {@link OutboxPublisher}.
 */
public class OutboxWriter {

    private static final Pattern AGGREGATE_PREFIX = Pattern.compile("^[A-Z][a-z0-9]*");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** Organización y actor del {@code TenantContext} de la operación en curso. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String eventType, UUID aggregateId, Object payload) {
        TenantInfo tenant = TenantContext.require();
        UUID actor = TenantContext.SYSTEM_USER_ID.equals(tenant.userId()) ? null : tenant.userId();
        return append(tenant.organizationId(), actor, eventType, aggregateId, payload);
    }

    /** Organización y actor explícitos (por ejemplo, al registrar una organización nueva). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(UUID organizationId, UUID actorId, String eventType, UUID aggregateId, Object payload) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        // La tabla tiene las columnas del ER (sin aggregate_type ni actor_id): el sobre completo va en payload y el
        // publicador lo envía tal cual.
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, occurredAt, organizationId,
                aggregateType(eventType), aggregateId.toString(), actorId, EventEnvelope.CURRENT_VERSION,
                json.valueToTree(payload));
        jdbc.update("""
                INSERT INTO outbox_events (id, event_type, aggregate_id, organization_id, correlation_id, payload,
                    occurred_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, eventId, eventType, aggregateId, organizationId, MDC.get(CorrelationId.MDC_KEY),
                json.valueToTree(envelope).toString(), Timestamp.from(occurredAt));
        return eventId;
    }

    /**
     * Tipo de agregado del sobre, deducido del tipo de evento: la primera palabra en mayúsculas
     * ({@code DiscrepancyOpened} → {@code DISCREPANCY}). La tabla {@code outbox_events} no lo guarda (v4, A.5).
     */
    static String aggregateType(String eventType) {
        Matcher matcher = AGGREGATE_PREFIX.matcher(eventType);
        return (matcher.find() ? matcher.group() : eventType).toUpperCase(Locale.ROOT);
    }
}
