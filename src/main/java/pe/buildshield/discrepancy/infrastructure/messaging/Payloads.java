package pe.buildshield.discrepancy.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import pe.buildshield.discrepancy.shared.messaging.EventEnvelope;

import java.math.BigDecimal;
import java.util.UUID;

/** Lectura tolerante de campos del payload: un campo ausente o mal formado se lee como nulo. */
final class Payloads {

    private Payloads() {
    }

    static JsonNode of(EventEnvelope event) {
        return event.payload() == null ? NullNode.getInstance() : event.payload();
    }

    static UUID uuid(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        try {
            return UUID.fromString(node.get(field).asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static String text(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.get(field).asText().isBlank() ? node.get(field).asText() : null;
    }

    static BigDecimal decimal(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        try {
            return new BigDecimal(node.get(field).asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
