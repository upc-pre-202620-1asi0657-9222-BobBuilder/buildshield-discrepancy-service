package pe.buildshield.discrepancy.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.discrepancy.shared.security.JwtTokenIssuer;
import pe.buildshield.discrepancy.shared.tenant.TenantInfo;
import pe.buildshield.discrepancy.support.TestEvents;
import pe.buildshield.discrepancy.support.TestEvents.Line;
import pe.buildshield.discrepancy.support.TestEvents.Reception;
import pe.buildshield.discrepancy.support.TestImages;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pe.buildshield.discrepancy.acceptance.ApiRequest.get;
import static pe.buildshield.discrepancy.acceptance.ApiRequest.post;

/** Pasos de US36 (discrepancias con evidencia, respuesta y resolución). */
public class DiscrepancySteps {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    ScenarioSession session;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtTokenIssuer issuer;

    @Autowired
    ObjectMapper json;

    private final UUID organization = UUID.randomUUID();
    private final Map<String, UUID> users = new HashMap<>();
    private final Map<String, String> roles = new HashMap<>();
    private final Map<String, UUID> places = new HashMap<>();
    private final Map<String, UUID> materials = new HashMap<>();
    private final Map<String, Reception> receptions = new HashMap<>();
    private final Map<String, String> sentConfirmations = new HashMap<>();
    private long sent;

    private TestEvents events() {
        return new TestEvents(json, organization);
    }

    @Dado("que en la organización trabajan {string} como {string}")
    public void user(String name, String role) {
        users.put(name, UUID.randomUUID());
        roles.put(name, role(role));
    }

    @Dado("que {string} está asignado a {string}")
    public void assigned(String name, String place) {
        UUID assignment = UUID.randomUUID();
        String type = roles.get(name).equals("WAREHOUSE_MANAGER") ? "WAREHOUSE" : "WORKSITE";
        send("buildshield.organization", assignment, events().assigned(assignment, users.get(name), place(place),
                type));
    }

    @Dado("que el catálogo tiene el material {string} con SKU {string} y tolerancia {string} %")
    public void material(String name, String sku, String tolerance) {
        UUID id = UUID.randomUUID();
        materials.put(sku, id);
        send("buildshield.organization", id, events().material(id, sku, name, tolerance));
    }

    @Dado("que {string} abrió la recepción del despacho {string} del {string} a {string}")
    public void receptionOpened(String name, String manifest, String warehouse, String worksite) {
        Reception reception = Reception.random(manifest, place(worksite), place(warehouse));
        receptions.put(manifest, reception);
        send("buildshield.ordering", reception.receptionId(), events().receptionOpened(reception, users.get(name)));
    }

    @Cuando("{string} confirma la recepción de {string} con:")
    public void confirm(String name, String manifest, DataTable table) {
        List<Line> lines = new ArrayList<>();
        for (Map<String, String> row : table.asMaps()) {
            String sku = row.get("sku");
            lines.add(Line.of(materials.computeIfAbsent(sku, ignored -> UUID.randomUUID()), sku, row.get("material"),
                    row.get("despachado"), row.get("recibido"), row.get("tolerancia")));
        }
        String confirmation = events().receptionConfirmed(UUID.randomUUID(), receptions.get(manifest),
                users.get(name), lines);
        sentConfirmations.put(manifest, confirmation);
        send("buildshield.ordering", receptions.get(manifest).receptionId(), confirmation);
    }

    @Cuando("la confirmación de {string} llega otra vez")
    public void confirmationAgain(String manifest) {
        send("buildshield.ordering", receptions.get(manifest).receptionId(), sentConfirmations.get(manifest));
        sent--;
        awaitSentinel(manifest);
    }

    @Cuando("{string} consulta las discrepancias")
    public void list(String name) throws Exception {
        awaitEvents();
        signIn(name);
        session.send(get("/api/v1/discrepancies"), null);
    }

    @Cuando("{string} consulta las discrepancias en estado {string}")
    public void listByStatus(String name, String status) throws Exception {
        awaitEvents();
        signIn(name);
        session.send(get("/api/v1/discrepancies").param("status", status), null);
    }

    @Entonces("ve {int} discrepancia(s)")
    public void sees(int count) throws Exception {
        session.expectStatus(200);
        assertThat(session.body().size()).isEqualTo(count);
    }

    @Entonces("ve la discrepancia {string} de {string} por {string} en estado {string}")
    public void seesDiscrepancy(String typeLabel, String sku, String quantity, String statusLabel) throws Exception {
        session.expectStatus(200);
        for (JsonNode discrepancy : session.body()) {
            if (discrepancy.path("sku").asText().equals(sku)) {
                assertThat(discrepancy.path("typeLabel").asText()).isEqualTo(typeLabel);
                assertThat(discrepancy.path("quantity").decimalValue()).isEqualByComparingTo(quantity);
                assertThat(discrepancy.path("statusLabel").asText()).isEqualTo(statusLabel);
                session.put("discrepancia " + sku, UUID.fromString(discrepancy.path("id").asText()));
                return;
            }
        }
        throw new AssertionError("No está la discrepancia de " + sku + ": " + session.body());
    }

    @Cuando("{string} registra la discrepancia {string} de {string} por {string} en {string} con la observación {string}")
    public void create(String name, String type, String sku, String quantity, String manifest, String observation)
            throws Exception {
        awaitEvents();
        signIn(name);
        session.send(post("/api/v1/discrepancies").idempotencyKey(session.key("alta " + sku)), Map.of(
                "receptionId", receptions.get(manifest).receptionId(), "materialId", materials.get(sku),
                "type", type(type), "quantity", quantity, "observation", observation));
        if (session.status() == 201) {
            session.put("discrepancia " + sku, UUID.fromString(session.body().path("id").asText()));
        }
    }

    @Cuando("{string} repite el registro de la discrepancia de {string} con la misma clave")
    public void repeatCreate(String name, String sku) throws Exception {
        String first = session.body().path("id").asText();
        session.send(post("/api/v1/discrepancies").idempotencyKey(session.key("alta " + sku)), Map.of(
                "receptionId", receptions.values().iterator().next().receptionId(), "materialId", materials.get(sku),
                "type", "DAMAGE", "quantity", "2", "observation", "Dos bolsas rotas en la descarga"));
        session.expectStatus(201);
        assertThat(session.last().replayed()).isEqualTo("true");
        assertThat(session.body().path("id").asText()).isEqualTo(first);
    }

    @Cuando("{string} sube la foto {string} de tipo {string} a la discrepancia de {string}")
    public void upload(String name, String fileName, String contentType, String sku) throws Exception {
        signIn(name);
        byte[] content = switch (contentType) {
            case "image/jpeg" -> TestImages.JPEG;
            case "image/png" -> TestImages.PNG;
            case "image/webp" -> TestImages.WEBP;
            default -> TestImages.PDF;
        };
        session.upload("/api/v1/discrepancies/" + session.id("discrepancia " + sku) + "/evidences", fileName,
                contentType, content);
    }

    @Cuando("{string} {string} la discrepancia de {string} con el texto {string}")
    public void action(String name, String action, String sku, String text) throws Exception {
        awaitEvents();
        signIn(name);
        String path = "/api/v1/discrepancies/" + session.id("discrepancia " + sku) + "/" + switch (action) {
            case "responde" -> "response";
            case "acepta" -> "accept";
            case "escala" -> "escalate";
            default -> "resolve";
        };
        Map<String, Object> body = switch (action) {
            case "responde" -> Map.of("text", text);
            case "escala" -> Map.of("reason", text);
            case "resuelve" -> Map.of("decision", text);
            default -> null;
        };
        session.send(post(path).idempotencyKey(UUID.randomUUID().toString()), body);
    }

    @Cuando("{string} consulta la discrepancia de {string}")
    public void consultOne(String name, String sku) throws Exception {
        signIn(name);
        session.send(get("/api/v1/discrepancies/" + session.id("discrepancia " + sku)), null);
    }

    @Entonces("la discrepancia queda en estado {string}")
    public void status(String statusLabel) throws Exception {
        session.expectStatus(200);
        assertThat(session.body().path("statusLabel").asText()).isEqualTo(statusLabel);
    }

    @Entonces("la discrepancia tiene la respuesta {string}, el escalamiento {string} y la decisión {string}")
    public void notes(String response, String reason, String decision) throws Exception {
        JsonNode body = session.body();
        assertThat(body.at("/response/text").asText()).isEqualTo(response);
        assertThat(body.at("/escalation/reason").asText()).isEqualTo(reason);
        assertThat(body.at("/resolution/decision").asText()).isEqualTo(decision);
    }

    @Entonces("la discrepancia tiene {int} evidencia(s) y la foto se puede descargar")
    public void evidences(int count) throws Exception {
        session.expectStatus(200);
        JsonNode evidences = session.body().path("evidences");
        assertThat(evidences.size()).isEqualTo(count);
        ApiResponse photo = session.perform(get(evidences.get(0).path("url").asText()), null, session.accessToken());
        assertThat(photo.status()).isEqualTo(200);
    }

    @Entonces("la respuesta tiene estado {int}")
    public void responseStatus(int status) {
        session.expectStatus(status);
    }

    @Entonces("la respuesta tiene estado {int} y código {string}")
    public void responseStatusAndCode(int status, String code) throws Exception {
        session.expectStatus(status);
        assertThat(session.body().path("code").asText()).isEqualTo(code);
    }

    @Entonces("se publicó el evento {string} de la discrepancia de {string}")
    public void published(String eventType, String sku) {
        String id = session.id("discrepancia " + sku).toString();
        await().atMost(EVENT_TIMEOUT).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?""", Long.class,
                eventType, id) == 1);
    }

    private void send(String topic, UUID key, String message) {
        kafka.send(topic, key.toString(), message);
        sent++;
    }

    /** Después de una entrega repetida: un evento nuevo en la misma partición confirma que la repetida ya pasó. */
    private void awaitSentinel(String manifest) {
        Reception reception = receptions.get(manifest);
        send("buildshield.ordering", reception.receptionId(), events().receptionOpened(reception, null));
        awaitEvents();
    }

    private void awaitEvents() {
        await().atMost(EVENT_TIMEOUT).until(() -> jdbc.queryForObject("SELECT count(*) FROM processed_events",
                Long.class) >= sent);
    }

    private void signIn(String name) {
        session.accessToken(issuer.issue(new TenantInfo(organization, users.get(name), roles.get(name))).value());
    }

    private UUID place(String name) {
        return places.computeIfAbsent(name, ignored -> UUID.randomUUID());
    }

    private static String type(String label) {
        return switch (label.toLowerCase()) {
            case "faltante" -> "SHORTAGE";
            case "daño" -> "DAMAGE";
            case "exceso" -> "EXCESS";
            default -> "WRONG_MATERIAL";
        };
    }

    private static String role(String displayName) {
        return switch (displayName.trim().toLowerCase()) {
            case "administrador" -> "ADMINISTRATOR";
            case "encargado de obra" -> "SITE_MANAGER";
            case "encargado de almacén", "encargado de almacen" -> "WAREHOUSE_MANAGER";
            default -> displayName;
        };
    }
}
