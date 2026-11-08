package pe.buildshield.discrepancy.shared.messaging;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * Tabla {@code processed_events}: los eventos que ya procesó cada consumidor del servicio, por
 * {@code (eventId, consumer)}. Un mismo evento lo pueden procesar dos consumidores distintos.
 */
public class ProcessedEvents {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcessedEvents(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Registra el evento dentro de la transacción en curso.
     *
     * @return {@code false} si ya estaba registrado (duplicado: no se procesa otra vez)
     */
    public boolean markProcessed(UUID eventId, String consumer) {
        return jdbc.update("""
                INSERT INTO processed_events (event_id, consumer, processed_at) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, eventId, consumer, Timestamp.from(clock.instant())) == 1;
    }

    public boolean isProcessed(UUID eventId, String consumer) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM processed_events WHERE event_id = ? AND consumer = ?)", Boolean.class,
                eventId, consumer));
    }
}
