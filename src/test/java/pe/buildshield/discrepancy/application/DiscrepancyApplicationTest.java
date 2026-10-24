package pe.buildshield.discrepancy.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.domain.model.Response;
import pe.buildshield.discrepancy.infrastructure.messaging.TestConsumers;
import pe.buildshield.discrepancy.shared.error.ConflictException;
import pe.buildshield.discrepancy.shared.error.ResourceNotFoundException;
import pe.buildshield.discrepancy.shared.error.ValidationException;
import pe.buildshield.discrepancy.shared.messaging.EventEnvelope;
import pe.buildshield.discrepancy.shared.messaging.OutboxWriter;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;
import pe.buildshield.discrepancy.shared.tenant.TenantInfo;
import pe.buildshield.discrepancy.support.MutableClock;
import pe.buildshield.discrepancy.support.TestEvents;
import pe.buildshield.discrepancy.support.TestEvents.Line;
import pe.buildshield.discrepancy.support.TestEvents.Reception;
import pe.buildshield.discrepancy.support.TestImages;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DiscrepancyApplicationTest {

    private static final Instant NOW = Instant.parse("2026-11-03T16:10:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private final UUID organization = UUID.randomUUID();
    private final TestEvents events = new TestEvents(json, organization);
    private final InMemoryDiscrepancies store = new InMemoryDiscrepancies();
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final DiscrepancyVisibility visibility = new DiscrepancyVisibility(store);
    private final DiscrepanciesCommandService commands = new DiscrepanciesCommandService(store, visibility, store,
            outbox, clock);
    private final TestConsumers consumers = new TestConsumers(commands, store);
    private final EvidenceUrlSigner signer = new EvidenceUrlSigner("secreto-de-pruebas-123", java.time.Duration.ofMinutes(30),
            clock);
    private final DiscrepanciesQueryService queries = new DiscrepanciesQueryService(store, visibility, store, signer,
            new pe.buildshield.discrepancy.support.TestTransactions());

    private final UUID ana = UUID.randomUUID();
    private final UUID rosa = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();
    private final UUID pedro = UUID.randomUUID();
    private final UUID torreNorte = UUID.randomUUID();
    private final UUID edificioSur = UUID.randomUUID();
    private final UUID almacen = UUID.randomUUID();
    private final UUID cement = UUID.randomUUID();
    private final UUID rebar = UUID.randomUUID();
    private final UUID sand = UUID.randomUUID();
    private final Reception reception = Reception.random("MAN-0001", torreNorte, almacen);

    @BeforeEach
    void assignments() throws Exception {
        handle(events.assigned(UUID.randomUUID(), luis, torreNorte, "WORKSITE"));
        handle(events.assigned(UUID.randomUUID(), pedro, edificioSur, "WORKSITE"));
        handle(events.assigned(UUID.randomUUID(), rosa, almacen, "WAREHOUSE"));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void confirmation_opens_one_discrepancy_per_line_out_of_tolerance_only_once() throws Exception {
        List<Line> lines = List.of(Line.of(cement, "CEM-001", "Cemento Portland", "40", "37", "2.5"),
                Line.of(rebar, "FIE-012", "Fierro 1/2", "100", "99", "2"),
                Line.of(sand, null, null, "10", "8", "5"));
        String confirmed = events.receptionConfirmed(UUID.randomUUID(), reception, luis, lines);

        assertThat(handle(confirmed)).isEqualTo(2);
        assertThat(handle(confirmed)).isZero();
        assertThat(handle(events.receptionConfirmed(UUID.randomUUID(), reception, luis, lines))).isZero();

        List<Discrepancy> opened = TenantContext.callAs(user("ADMINISTRATOR", ana),
                () -> queries.list(null, null, null));
        assertThat(opened).hasSize(2).allSatisfy(d -> {
            assertThat(d.type()).isEqualTo(DiscrepancyType.SHRINKAGE);
            assertThat(d.openedBy()).isEqualTo(luis);
            assertThat(d.reception().manifestCode()).isEqualTo("MAN-0001");
            assertThat(d.material().unit()).isEqualTo("BAG");
            assertThat(d.reason()).hasSizeLessThanOrEqualTo(60);
        });
        Discrepancy cementShrinkage = opened.stream().filter(d -> d.material().materialId().equals(cement))
                .findFirst().orElseThrow();
        assertThat(cementShrinkage.quantity()).isEqualByComparingTo("3");
        assertThat(cementShrinkage.reason()).isEqualTo("Merma de 7.5 % sobre 2.5 % tolerado");
        Discrepancy sandShrinkage = opened.stream().filter(d -> d.material().materialId().equals(sand)).findFirst()
                .orElseThrow();
        assertThat(sandShrinkage.material().sku()).isEqualTo("SIN-SKU");
        ArgumentCaptor<DiscrepancyEvents.DiscrepancyChanged> payload =
                ArgumentCaptor.forClass(DiscrepancyEvents.DiscrepancyChanged.class);
        verify(outbox, times(2)).append(eq(organization), eq(luis), eq("DiscrepancyOpened"), any(),
                payload.capture());
        assertThat(payload.getAllValues()).allSatisfy(changed -> {
            assertThat(changed.unit()).isEqualTo("BAG");
            assertThat(changed.status()).isEqualTo("OPEN");
            assertThat(changed.dispatchId()).isEqualTo(reception.dispatchId());
        });
    }

    @Test
    void the_type_is_shortage_surplus_or_shrinkage() throws Exception {
        UUID bricks = UUID.randomUUID();
        UUID pipes = UUID.randomUUID();
        handle(events.receptionConfirmed(UUID.randomUUID(), reception, luis, List.of(
                Line.of(bricks, "LAD-001", "Ladrillo", "500", "480", "0"),
                Line.of(pipes, "TUB-002", "Tubo PVC", "20", "24", "5"),
                Line.of(cement, "CEM-001", "Cemento Portland", "40", "37", "2.5"))));

        List<Discrepancy> opened = TenantContext.callAs(user("ADMINISTRATOR", ana),
                () -> queries.list(null, null, null));
        assertThat(opened).extracting(d -> d.material().materialId(), Discrepancy::type, Discrepancy::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(bricks, DiscrepancyType.SHORTAGE,
                                "Faltan 20 BAG de 500 despachados"),
                        org.assertj.core.groups.Tuple.tuple(pipes, DiscrepancyType.SURPLUS,
                                "Sobran 4 BAG de 20 despachados"),
                        org.assertj.core.groups.Tuple.tuple(cement, DiscrepancyType.SHRINKAGE,
                                "Merma de 7.5 % sobre 2.5 % tolerado"));
    }

    @Test
    void confirmation_without_reception_data_or_with_bad_lines_opens_nothing() throws Exception {
        String incomplete = events.envelope(UUID.randomUUID(), "ReceptionConfirmed", "RECEPTION", "x", null,
                TestEvents.map("receptionId", UUID.randomUUID()));
        assertThat(handle(incomplete)).isZero();

        String noFlag = events.envelope(UUID.randomUUID(), "ReceptionConfirmed", "RECEPTION", reception.receptionId(),
                null, TestEvents.map("receptionId", reception.receptionId(), "dispatchId", reception.dispatchId(),
                        "worksiteId", torreNorte, "warehouseId", almacen, "lines", List.of(
                                TestEvents.map("receptionLineId", UUID.randomUUID(), "materialId", cement,
                                        "dispatched", 10, "received", 9, "shrinkagePercent", 10, "tolerancePercent", 2.5),
                                TestEvents.map("materialId", rebar, "dispatched", 10, "received", 1,
                                        "withinTolerance", false),
                                TestEvents.map("receptionLineId", UUID.randomUUID(), "dispatched", 10,
                                        "received", 1, "withinTolerance", false),
                                TestEvents.map("receptionLineId", UUID.randomUUID(), "materialId", rebar,
                                        "dispatched", 10, "received", 10, "withinTolerance", false),
                                TestEvents.map("receptionLineId", "no-es-uuid", "materialId", rebar,
                                        "dispatched", 10, "received", 1, "withinTolerance", false),
                                TestEvents.map("receptionLineId", UUID.randomUUID(), "materialId", sand,
                                        "dispatched", "n/d", "received", 1, "withinTolerance", false))));
        assertThat(handle(noFlag)).isEqualTo(2);
        Discrepancy fromNoFlag = TenantContext.callAs(user("ADMINISTRATOR", ana), () -> queries.list(null, null, null))
                .stream().filter(d -> d.material().materialId().equals(cement)).findFirst().orElseThrow();
        assertThat(fromNoFlag.material().name()).startsWith("Material ");
        assertThat(fromNoFlag.material().unit()).isNull();
        assertThat(fromNoFlag.openedBy()).isEqualTo(TenantContext.SYSTEM_USER_ID);
        verify(outbox, times(2)).append(eq(organization), isNull(), eq("DiscrepancyOpened"), any(), any());
    }

    @Test
    void unknown_events_and_incomplete_assignments_are_ignored() throws Exception {
        assertThat(handle(events.envelope(UUID.randomUUID(), "OrderApproved", "ORDER", "o", null,
                TestEvents.map("orderId", UUID.randomUUID())))).isZero();
        handle(events.envelope(UUID.randomUUID(), "StaffAssigned", "ASSIGNMENT", "a", null, TestEvents.map()));
        handle(events.envelope(UUID.randomUUID(), "StaffUnassigned", "ASSIGNMENT", "a", null, TestEvents.map()));
        handle(events.envelope(UUID.randomUUID(), "StaffUpdated", "ASSIGNMENT", "a", null, null));
        UUID assignment = UUID.randomUUID();
        handle(events.assigned(assignment, pedro, torreNorte, "WORKSITE"));
        handle(events.unassigned(assignment, pedro, torreNorte, "WORKSITE"));
        assertThat(store.assignedLocations(organization, pedro, "WORKSITE")).containsExactly(edificioSur);
    }

    @Test
    void the_full_flow_respond_escalate_resolve_with_visibility_by_role() throws Exception {
        handle(events.receptionConfirmed(UUID.randomUUID(), reception, luis,
                List.of(Line.of(cement, "CEM-001", "Cemento Portland", "40", "37", "2.5"))));
        UUID id = store.byId.keySet().iterator().next();

        assertThatThrownBy(() -> TenantContext.callAs(user("WAREHOUSE_MANAGER", UUID.randomUUID()),
                () -> commands.respond(id, "No es mío"))).isInstanceOf(ResourceNotFoundException.class);
        Discrepancy responded = TenantContext.callAs(user("WAREHOUSE_MANAGER", rosa),
                () -> commands.respond(id, "Se repone mañana"));
        assertThat(responded.status()).isEqualTo(DiscrepancyStatus.RESPONDED);
        assertThat(responded.response().orElseThrow().authorId()).isEqualTo(rosa);

        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", pedro),
                () -> commands.escalate(id, "No es su obra"))).isInstanceOf(ResourceNotFoundException.class);
        clock.advance(java.time.Duration.ofHours(1));
        Discrepancy escalated = TenantContext.callAs(user("SITE_MANAGER", luis),
                () -> commands.escalate(id, "No llegó la reposición"));
        assertThat(escalated.status()).isEqualTo(DiscrepancyStatus.ESCALATED);
        assertThat(escalated.escalatedAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.accept(id)))
                .isInstanceOf(ConflictException.class);

        clock.advance(java.time.Duration.ofHours(1));
        Discrepancy resolved = TenantContext.callAs(user("ADMINISTRATOR", ana),
                () -> commands.resolve(id, "Se descuenta al transportista"));
        assertThat(resolved.status()).isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(resolved.resolution().orElseThrow().decision()).isEqualTo("Se descuenta al transportista");
        assertThat(resolved.responses()).extracting(Response::decision).containsExactly(Response.Decision.RESPOND,
                Response.Decision.ESCALATE, Response.Decision.RESOLVE);
        verify(outbox).append(eq(organization), eq(rosa), eq("DiscrepancyResponded"), eq(id), any());
        verify(outbox).append(eq(organization), eq(luis), eq("DiscrepancyEscalated"), eq(id), any());
        verify(outbox).append(eq(organization), eq(ana), eq("DiscrepancyResolved"), eq(id), any());
    }

    @Test
    void respond_then_accept_closes_and_lists_are_filtered_by_role() throws Exception {
        handle(events.receptionConfirmed(UUID.randomUUID(), reception, luis,
                List.of(Line.of(cement, "CEM-001", "Cemento Portland", "40", "37", "2.5"))));
        Reception other = Reception.random("MAN-0002", edificioSur, UUID.randomUUID());
        handle(events.receptionConfirmed(UUID.randomUUID(), other, pedro,
                List.of(Line.of(cement, "CEM-001", "Cemento Portland", "10", "5", "2.5"))));
        assertThat(TenantContext.callAs(user("ADMINISTRATOR", ana), () -> queries.list(null, null, null))).hasSize(2);
        List<Discrepancy> luisSees = TenantContext.callAs(user("SITE_MANAGER", luis),
                () -> queries.list(DiscrepancyStatus.OPEN, null, null));
        assertThat(luisSees).hasSize(1);
        assertThat(TenantContext.callAs(user("WAREHOUSE_MANAGER", rosa), () -> queries.list(null, null, null)))
                .hasSize(1);
        assertThat(TenantContext.callAs(user("WAREHOUSE_MANAGER", UUID.randomUUID()),
                () -> queries.list(null, null, null))).isEmpty();
        assertThat(TenantContext.callAs(user("SYSTEM", UUID.randomUUID()), () -> queries.list(null, null, null)))
                .isEmpty();
        assertThat(TenantContext.callAs(user("ADMINISTRATOR", ana),
                () -> queries.list(null, null, other.dispatchId()))).hasSize(1);
        assertThat(TenantContext.callAs(user("ADMINISTRATOR", ana),
                () -> queries.list(null, edificioSur, null))).hasSize(1);

        UUID id = luisSees.get(0).id();
        TenantContext.runAs(user("WAREHOUSE_MANAGER", rosa), () -> commands.respond(id, "Se repone"));
        Discrepancy accepted = TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.accept(id));
        assertThat(accepted.status()).isEqualTo(DiscrepancyStatus.ACCEPTED);
        assertThat(accepted.responses()).extracting(Response::decision).containsExactly(Response.Decision.RESPOND,
                Response.Decision.ACCEPT);
        assertThat(accepted.escalatedAt()).isNull();
        assertThat(accepted.resolution()).isEmpty();
        assertThat(TenantContext.callAs(user("SITE_MANAGER", luis), () -> queries.get(id)).status())
                .isEqualTo(DiscrepancyStatus.ACCEPTED);
        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", pedro), () -> queries.get(id)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> TenantContext.callAs(new TenantInfo(UUID.randomUUID(), ana, "ADMINISTRATOR"),
                () -> queries.get(id))).isInstanceOf(ResourceNotFoundException.class);
        verify(outbox).append(eq(organization), eq(luis), eq("DiscrepancyAccepted"), eq(id), any());
    }

    @Test
    void evidence_photos_are_checked_stored_and_read_back() throws Exception {
        handle(events.receptionConfirmed(UUID.randomUUID(), reception, luis,
                List.of(Line.of(cement, "CEM-001", "Cemento Portland", "40", "37", "2.5"))));
        UUID id = store.byId.keySet().iterator().next();
        TenantInfo luisSession = user("SITE_MANAGER", luis);

        Evidence evidence = TenantContext.callAs(luisSession, () -> commands.addEvidence(id,
                new DiscrepanciesCommandService.EvidenceUpload("bolsas rotas.jpg", "image/jpeg", TestImages.JPEG)));
        assertThat(evidence.photoUrl()).isEqualTo(organization + "/" + id + "/" + evidence.id() + ".jpg");
        assertThat(evidence.fileName()).isEqualTo(evidence.id() + ".jpg");
        assertThat(evidence.contentType()).isEqualTo("image/jpeg");
        Evidence png = TenantContext.callAs(luisSession, () -> commands.addEvidence(id,
                new DiscrepanciesCommandService.EvidenceUpload(null, "IMAGE/PNG", TestImages.PNG)));
        clock.advance(java.time.Duration.ofSeconds(1));
        TenantContext.callAs(luisSession, () -> commands.addEvidence(id,
                new DiscrepanciesCommandService.EvidenceUpload("a/b:c.webp", "image/webp", TestImages.WEBP)));

        Discrepancy withPhotos = TenantContext.callAs(luisSession, () -> queries.get(id));
        assertThat(withPhotos.evidences()).hasSize(3);
        assertThat(png.contentType()).isEqualTo("image/png");
        assertThat(withPhotos.evidences().get(2).contentType()).isEqualTo("image/webp");
        var file = TenantContext.callAs(user("WAREHOUSE_MANAGER", rosa), () -> queries.evidence(id, evidence.id()));
        assertThat(file.content()).isEqualTo(TestImages.JPEG);

        String signed = signer.signedUrl(organization, id, evidence);
        String token = signed.substring(signed.indexOf("?token=") + 7);
        assertThat(signed).startsWith(EvidenceUrlSigner.contentPath(id, evidence.id()) + "?token=" + organization
                + ".");
        assertThat(queries.evidenceWithToken(id, evidence.id(), token).content()).isEqualTo(TestImages.JPEG);
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(() -> queries.evidenceWithToken(id, UUID.randomUUID(), token))
                .isInstanceOf(ResourceNotFoundException.class);
        String otherEvidenceToken = signer.signedUrl(organization, id, new Evidence(UUID.randomUUID(), "k.jpg", NOW))
                .split("token=")[1];
        assertThatThrownBy(() -> queries.evidenceWithToken(id, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                otherEvidenceToken)).isInstanceOf(ResourceNotFoundException.class);
        clock.advance(java.time.Duration.ofMinutes(31));
        assertThatThrownBy(() -> queries.evidenceWithToken(id, evidence.id(), token))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> TenantContext.callAs(luisSession, () -> queries.evidence(id, UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
        store.files.clear();
        assertThatThrownBy(() -> TenantContext.callAs(luisSession, () -> queries.evidence(id, evidence.id())))
                .isInstanceOf(ResourceNotFoundException.class);

        assertRejected(id, "image/jpeg", TestImages.PDF, "EVIDENCE_TYPE_NOT_ALLOWED");
        assertRejected(id, "application/pdf", TestImages.PDF, "EVIDENCE_TYPE_NOT_ALLOWED");
        assertRejected(id, null, TestImages.JPEG, "EVIDENCE_TYPE_NOT_ALLOWED");
        assertRejected(id, "image/png", new byte[]{1}, "EVIDENCE_TYPE_NOT_ALLOWED");
        assertRejected(id, "image/jpeg", new byte[0], "EVIDENCE_EMPTY");
        assertRejected(id, "image/jpeg", null, "EVIDENCE_EMPTY");
        byte[] huge = new byte[(int) DiscrepanciesCommandService.MAX_EVIDENCE_BYTES + 1];
        System.arraycopy(TestImages.JPEG, 0, huge, 0, TestImages.JPEG.length);
        assertRejected(id, "image/jpeg", huge, "EVIDENCE_TOO_LARGE");
    }

    private void assertRejected(UUID id, String contentType, byte[] content, String code) {
        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.addEvidence(id,
                new DiscrepanciesCommandService.EvidenceUpload("x", contentType, content))))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getCode()).isEqualTo(code));
    }

    private TenantInfo user(String role, UUID userId) {
        return new TenantInfo(organization, userId, role);
    }

    private int handle(String envelope) throws Exception {
        JsonNode node = json.readTree(envelope);
        EventEnvelope event = new EventEnvelope(UUID.fromString(node.get("eventId").asText()),
                node.get("eventType").asText(), Instant.parse(node.get("occurredAt").asText()), organization,
                node.get("aggregateType").asText(), node.get("aggregateId").asText(),
                node.get("actorId").isNull() ? null : UUID.fromString(node.get("actorId").asText()), 1,
                node.get("payload").isNull() ? null : node.get("payload"));
        return TenantContext.callAs(new TenantInfo(organization, TenantContext.SYSTEM_USER_ID, "SYSTEM"),
                () -> consumers.handle(event));
    }
}
