package pe.buildshield.discrepancy.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.discrepancy.application.DiscrepanciesCommandService;
import pe.buildshield.discrepancy.application.DiscrepanciesQueryService;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;
import pe.buildshield.discrepancy.shared.tenant.TenantInfo;
import pe.buildshield.discrepancy.support.DiscrepancyIntegrationTest;
import pe.buildshield.discrepancy.support.TestDatabase;
import pe.buildshield.discrepancy.support.TestEvents;
import pe.buildshield.discrepancy.support.TestEvents.Line;
import pe.buildshield.discrepancy.support.TestEvents.Reception;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * QAS10 contra Kafka embebido y PostgreSQL real: {@code ReceptionConfirmed} entregado dos veces, y otra vez con otro
 * eventId, abre una sola discrepancia por línea fuera de tolerancia; el outbox publica {@code DiscrepancyOpened} con
 * los campos que leen notification y audit. Después se sube una evidencia y se responde, escala y resuelve.
 */
@DiscrepancyIntegrationTest
class DiscrepancyMessagingIT {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    DiscrepanciesOutboxPublisher publisher;

    @Autowired
    DiscrepanciesQueryService queries;

    @Autowired
    DiscrepanciesCommandService commands;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @BeforeEach
    void clean() {
        TestDatabase.reset(jdbc);
    }

    @Test
    void repeated_confirmation_opens_one_discrepancy_per_out_of_tolerance_line_and_publishes_it() throws Exception {
        UUID organization = UUID.randomUUID();
        TestEvents events = new TestEvents(json, organization);
        UUID worksite = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID luis = UUID.randomUUID();
        UUID cement = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        Reception reception = Reception.random("MAN-0007", worksite, warehouse);
        kafka.send("buildshield.organization", cement.toString(), events.material(cement, "CEM-001",
                "Cemento Portland", "2.5"));
        kafka.send("buildshield.organization", assignment.toString(), events.assigned(assignment, luis, worksite,
                "WORKSITE"));
        List<Line> lines = List.of(Line.of(cement, "CEM-001", "Cemento Portland", "40", "37", "2.5"),
                Line.of(UUID.randomUUID(), "ARE-001", "Arena gruesa", "10", "10", "5"),
                Line.of(UUID.randomUUID(), "LAD-001", "Ladrillo King Kong", "1000", "900", "3"));
        String confirmed = events.receptionConfirmed(UUID.randomUUID(), reception, luis, lines);

        kafka.send("buildshield.ordering", reception.receptionId().toString(), confirmed);
        kafka.send("buildshield.ordering", reception.receptionId().toString(), confirmed);
        kafka.send("buildshield.ordering", reception.receptionId().toString(),
                events.receptionConfirmed(UUID.randomUUID(), reception, luis, lines));

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events", Long.class) == 4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discrepancies WHERE organization_id = ?", Long.class,
                organization)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT reception_line_id) FROM discrepancies", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT consumer FROM processed_events", String.class))
                .containsExactlyInAnyOrder("AssignmentEventConsumer", "AssignmentEventConsumer",
                        "ReceptionEventConsumer", "ReceptionEventConsumer");

        TenantInfo luisSession = new TenantInfo(organization, luis, "SITE_MANAGER");
        List<Discrepancy> visible = TenantContext.callAs(luisSession, () -> queries.list(null, null, null));
        assertThat(visible).hasSize(2);
        assertThat(visible).extracting(d -> d.material().sku()).containsExactlyInAnyOrder("CEM-001", "LAD-001");

        assertThat(publisher.publishPending()).isEqualTo(2);
        List<JsonNode> published = read(organization, 2);
        JsonNode opened = published.stream().filter(e -> e.at("/payload/sku").asText().equals("CEM-001")).findFirst()
                .orElseThrow();
        assertThat(opened.get("eventType").asText()).isEqualTo("DiscrepancyOpened");
        assertThat(opened.get("aggregateType").asText()).isEqualTo("DISCREPANCY");
        assertThat(opened.get("actorId").asText()).isEqualTo(luis.toString());
        assertThat(opened.at("/payload/dispatchId").asText()).isEqualTo(reception.dispatchId().toString());
        assertThat(opened.at("/payload/receptionId").asText()).isEqualTo(reception.receptionId().toString());
        assertThat(opened.at("/payload/worksiteId").asText()).isEqualTo(worksite.toString());
        assertThat(opened.at("/payload/warehouseId").asText()).isEqualTo(warehouse.toString());
        assertThat(opened.at("/payload/manifestCode").asText()).isEqualTo("MAN-0007");
        assertThat(opened.at("/payload/materialName").asText()).isEqualTo("Cemento Portland");
        assertThat(opened.at("/payload/status").asText()).isEqualTo("OPEN");
        assertThat(opened.at("/payload/type").asText()).isEqualTo("SHRINKAGE");
        assertThat(opened.at("/payload/unit").asText()).isEqualTo("BAG");
        assertThat(opened.at("/payload/openedBy").asText()).isEqualTo(luis.toString());
        assertThat(opened.at("/payload/quantity").decimalValue()).isEqualByComparingTo("3");
        assertThat(opened.at("/payload/text").asText()).isEqualTo("Merma de 7.5 % sobre 2.5 % tolerado");

        UUID id = UUID.fromString(opened.at("/payload/discrepancyId").asText());
        Discrepancy withPhoto = TenantContext.callAs(luisSession, () -> {
            commands.addEvidence(id, new DiscrepanciesCommandService.EvidenceUpload("foto.png", "image/png",
                    pe.buildshield.discrepancy.support.TestImages.PNG));
            return queries.get(id);
        });
        assertThat(withPhoto.evidences()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT organization_id FROM discrepancy_evidences", UUID.class))
                .isEqualTo(organization);
        assertThat(TenantContext.callAs(luisSession, () -> queries.evidence(id, withPhoto.evidences().get(0).id()))
                .content()).isEqualTo(pe.buildshield.discrepancy.support.TestImages.PNG);
        assertThat(jdbc.queryForObject("SELECT photo_url FROM discrepancy_evidences", String.class))
                .isEqualTo(organization + "/" + id + "/" + withPhoto.evidences().get(0).id() + ".png");

        TenantInfo admin = new TenantInfo(organization, UUID.randomUUID(), "ADMINISTRATOR");
        TenantContext.callAs(admin, () -> commands.respond(id, "Se repone mañana"));
        TenantContext.callAs(luisSession, () -> commands.escalate(id, "No llegó la reposición"));
        Discrepancy resolved = TenantContext.callAs(admin, () -> commands.resolve(id, "Se descuenta al transportista"));
        assertThat(resolved.status()).isEqualTo(pe.buildshield.discrepancy.domain.model.DiscrepancyStatus.RESOLVED);
        assertThat(resolved.evidences()).hasSize(1);
        assertThat(resolved.responses()).hasSize(3);
        assertThat(jdbc.queryForList("SELECT decision FROM discrepancy_responses WHERE discrepancy_id = ? "
                + "ORDER BY created_at, decision", String.class, id)).containsExactly("RESPOND", "ESCALATE", "RESOLVE");
        assertThat(jdbc.queryForObject("SELECT escalated_at IS NOT NULL AND resolved_at IS NOT NULL FROM discrepancies "
                + "WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(publisher.publishPending()).isEqualTo(3);
    }

    @Test
    void reception_events_feed_the_copy_idempotently_and_a_manual_report_flows_like_an_automatic_one() throws Exception {
        UUID organization = UUID.randomUUID();
        TestEvents events = new TestEvents(json, organization);
        UUID worksite = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID luis = UUID.randomUUID();
        UUID cement = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        Reception reception = Reception.random("MAN-0008", worksite, warehouse);
        Reception unknown = Reception.random("MAN-0009", worksite, warehouse);
        String opened = events.receptionOpened(reception, luis);
        String key = reception.receptionId().toString();
        Line line = Line.of(cement, "CEM-001", "Cemento Portland", "40", "37", "2.5");
        String confirmed = events.receptionConfirmed(UUID.randomUUID(), reception, luis, List.of(line));

        kafka.send("buildshield.organization", assignment.toString(), events.assigned(assignment, luis, worksite,
                "WORKSITE"));
        kafka.send("buildshield.ordering", key, opened);
        kafka.send("buildshield.ordering", key, opened);
        kafka.send("buildshield.ordering", key, confirmed);
        kafka.send("buildshield.ordering", key, confirmed);
        kafka.send("buildshield.ordering", key, events.envelope(UUID.randomUUID(), "ReceptionPartiallyReceived",
                "RECEPTION", reception.receptionId(), luis, TestEvents.map("receptionId", reception.receptionId())));
        kafka.send("buildshield.ordering", unknown.receptionId().toString(), events.receptionConfirmed(
                UUID.randomUUID(), unknown, luis, List.of(Line.of(cement, "CEM-001", "Cemento Portland", "10", "10",
                        "2.5"))));
        kafka.send("buildshield.ordering", key, events.envelope(UUID.randomUUID(), "ReceptionOpened", "RECEPTION",
                "x", null, TestEvents.map("receptionId", UUID.randomUUID())));

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events", Long.class) == 6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reception_projection WHERE organization_id = ?",
                Long.class, organization)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reception_line_projection", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT sku, material_name, shrinkage_percent, tolerance_percent FROM "
                + "reception_line_projection WHERE reception_line_id = ?", line.receptionLineId()))
                .containsEntry("sku", "CEM-001").containsEntry("material_name", "Cemento Portland")
                .containsEntry("shrinkage_percent", new java.math.BigDecimal("7.50"))
                .containsEntry("tolerance_percent", new java.math.BigDecimal("2.50"));

        Map<String, Object> automatic = jdbc.queryForMap("SELECT origin, observation, shrinkage_pct, tolerance_pct, "
                + "reception_line_id FROM discrepancies");
        assertThat(automatic).containsEntry("origin", "AUTOMATIC").containsEntry("observation", null)
                .containsEntry("shrinkage_pct", new java.math.BigDecimal("7.50"))
                .containsEntry("tolerance_pct", new java.math.BigDecimal("2.50"))
                .containsEntry("reception_line_id", line.receptionLineId());

        TenantInfo luisSession = new TenantInfo(organization, luis, "SITE_MANAGER");
        Discrepancy manual = TenantContext.callAs(luisSession, () -> commands.create(
                new DiscrepanciesCommandService.CreateDiscrepancy(reception.receptionId(), cement,
                        pe.buildshield.discrepancy.domain.model.DiscrepancyType.DAMAGE, new java.math.BigDecimal("2"),
                        "Dos bolsas rotas en la descarga")));
        Map<String, Object> row = jdbc.queryForMap("SELECT origin, observation, reason, reception_line_id, type, "
                + "manifest_code, sku, material_name, unit, shrinkage_pct, tolerance_pct FROM discrepancies WHERE id = ?",
                manual.id());
        assertThat(row).containsEntry("origin", "MANUAL").containsEntry("reception_line_id", null)
                .containsEntry("observation", "Dos bolsas rotas en la descarga")
                .containsEntry("reason", "Dos bolsas rotas en la descarga").containsEntry("type", "DAMAGE")
                .containsEntry("manifest_code", "MAN-0008").containsEntry("sku", "CEM-001")
                .containsEntry("material_name", "Cemento Portland").containsEntry("unit", null)
                .containsEntry("shrinkage_pct", new java.math.BigDecimal("7.50"))
                .containsEntry("tolerance_pct", new java.math.BigDecimal("2.50"));
        Discrepancy reread = TenantContext.callAs(luisSession, () -> queries.get(manual.id()));
        assertThat(reread.origin()).isEqualTo(pe.buildshield.discrepancy.domain.model.DiscrepancyOrigin.MANUAL);
        assertThat(reread.observation()).isEqualTo("Dos bolsas rotas en la descarga");

        assertThat(publisher.publishPending()).isEqualTo(2);
        JsonNode published = read(organization, 2).stream().filter(e -> e.at("/payload/type").asText().equals("DAMAGE"))
                .findFirst().orElseThrow();
        assertThat(published.get("eventType").asText()).isEqualTo("DiscrepancyOpened");
        assertThat(published.get("actorId").asText()).isEqualTo(luis.toString());
        assertThat(published.at("/payload/text").asText()).isEqualTo("Dos bolsas rotas en la descarga");
        assertThat(published.at("/payload/receptionLineId").isNull()).isTrue();
        assertThat(published.at("/payload/manifestCode").asText()).isEqualTo("MAN-0008");
    }

    private List<JsonNode> read(UUID organizationId, int expected) throws Exception {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "discrepancy-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<JsonNode> found = new ArrayList<>();
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            consumer.subscribe(List.of("buildshield.discrepancy"));
            long deadline = System.currentTimeMillis() + 20_000;
            while (found.size() < expected && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode envelope = json.readTree(record.value());
                    if (envelope.get("organizationId").asText().equals(organizationId.toString())) {
                        assertThat(record.key()).isEqualTo(envelope.get("aggregateId").asText());
                        found.add(envelope);
                    }
                }
            }
        }
        assertThat(found).hasSize(expected);
        return found;
    }
}
