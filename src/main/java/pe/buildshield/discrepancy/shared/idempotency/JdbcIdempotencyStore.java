package pe.buildshield.discrepancy.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Tabla {@code idempotency_keys} del Anexo A.5: {@code endpoint} es {@code "MÉTODO ruta"}, {@code request_hash} la
 * huella SHA-256 y {@code response_body} un JSON con el estado HTTP, el tipo, la ubicación, el cuerpo y el dueño de la
 * operación. Al retirar la respuesta se conserva el dueño. El advisory lock dura la transacción; la identidad de una
 * operación nunca se elimina.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    static final int MAX_ENDPOINT = 80;
    static final String COMPLETED = "COMPLETED";
    static final String EXPIRED = "EXPIRED";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, row) -> {
        String endpoint = rs.getString("endpoint");
        int space = endpoint.indexOf(' ');
        JsonNode response = read(rs.getString("response_body"));
        Instant createdAt = rs.getTimestamp("expires_at").toInstant().minus(IdempotencyKeyFilter.RETENTION);
        return new IdempotencyRecord(rs.getObject("organization_id", UUID.class), rs.getObject("key", UUID.class),
                endpoint.substring(0, space), endpoint.substring(space + 1), response.path("status").asInt(),
                text(response, "contentType"), body(response), createdAt, uuid(response, "userId"),
                text(response, "role"), rs.getString("request_hash"), text(response, "location"),
                EXPIRED.equals(rs.getString("status")));
    };

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryLock(UUID organizationId, UUID key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                lockId(organizationId, key)));
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID organizationId, UUID key) {
        return jdbc.query("SELECT * FROM idempotency_keys WHERE organization_id = ? AND key = ?", ROW_MAPPER,
                organizationId, key).stream().findFirst();
    }

    @Override
    public void save(IdempotencyRecord r) {
        String endpoint = endpoint(r.requestMethod(), r.requestPath());
        jdbc.update("""
                INSERT INTO idempotency_keys (key, organization_id, endpoint, request_hash, status, response_body,
                    expires_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, r.key(), r.organizationId(), endpoint, r.requestFingerprint(),
                r.responseExpired() ? EXPIRED : COMPLETED, responseBody(r),
                Timestamp.from(r.createdAt().plus(IdempotencyKeyFilter.RETENTION)));
    }

    /** Retira solo las respuestas; conserva las marcas y el dueño, que impiden duplicaciones tardías. */
    @Override
    public int retireResponsesCreatedBefore(Instant threshold) {
        return jdbc.update("""
                UPDATE idempotency_keys
                SET status = 'EXPIRED', response_body = response_body - 'body' - 'bodyBase64' - 'contentType' - 'location'
                WHERE status = 'COMPLETED' AND expires_at < ?
                """, Timestamp.from(threshold.plus(IdempotencyKeyFilter.RETENTION)));
    }

    static String endpoint(String method, String path) {
        String endpoint = method + " " + path;
        if (endpoint.length() > MAX_ENDPOINT) {
            throw new IllegalArgumentException("La ruta " + path + " no cabe en idempotency_keys.endpoint");
        }
        return endpoint;
    }

    /** Cuerpo de texto (JSON) tal cual, para reproducir los mismos bytes; si no es texto, en Base64. */
    static String responseBody(IdempotencyRecord r) {
        ObjectNode node = JSON.createObjectNode();
        node.put("status", r.responseStatus());
        node.put("contentType", r.responseContentType());
        node.put("location", r.responseLocation());
        node.put("userId", r.userId() == null ? null : r.userId().toString());
        node.put("role", r.role());
        if (r.responseBody() != null) {
            if (isText(r.responseContentType())) {
                node.put("body", new String(r.responseBody(), StandardCharsets.UTF_8));
            } else {
                node.put("bodyBase64", Base64.getEncoder().encodeToString(r.responseBody()));
            }
        }
        return node.toString();
    }

    private static boolean isText(String contentType) {
        return contentType != null && (contentType.contains("json") || contentType.startsWith("text/"));
    }

    private static JsonNode read(String value) {
        try {
            return value == null ? JSON.createObjectNode() : JSON.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("response_body de idempotency_keys no es JSON", ex);
        }
    }

    private static byte[] body(JsonNode response) {
        if (response.hasNonNull("body")) {
            return response.get("body").asText().getBytes(StandardCharsets.UTF_8);
        }
        return response.hasNonNull("bodyBase64") ? Base64.getDecoder().decode(response.get("bodyBase64").asText())
                : null;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static UUID uuid(JsonNode node, String field) {
        return node.hasNonNull(field) ? UUID.fromString(node.get(field).asText()) : null;
    }

    static long lockId(UUID organizationId, UUID key) {
        long hash = 1125899906842597L;
        for (long part : new long[] {organizationId.getMostSignificantBits(), organizationId.getLeastSignificantBits(),
                key.getMostSignificantBits(), key.getLeastSignificantBits()}) hash = 31 * hash + part;
        return hash;
    }
}
