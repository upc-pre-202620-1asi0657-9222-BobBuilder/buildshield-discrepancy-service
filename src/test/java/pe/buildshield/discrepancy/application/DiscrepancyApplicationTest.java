package pe.buildshield.discrepancy.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;
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

import java.math.BigDecimal;
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
    private final DiscrepancyCommandService commands = new DiscrepancyCommandService(store, store, visibility, store,
            outbox, clock);
    private final DiscrepancyEventHandler handler = new DiscrepancyEventHandler(store, commands);
    private final DiscrepancyQueryService queries = new DiscrepancyQueryService(store, visibility, store);

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
    void copies() throws Exception {
        handle(events.material(cement, "CEM-001", "Cemento Portland", "2.5"));
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
            assertThat(d.type()).isEqualTo(DiscrepancyType.SHORTAGE);
            assertThat(d.openedBy()).isEqualTo(luis);
            assertThat(d.reception().manifestCode()).isEqualTo("MAN-0001");
        });
        Discrepancy cementShortage = opened.stream().filter(d -> d.material().materialId().equals(cement)).findFirst()
                .orElseThrow();
        assertThat(cementShortage.quantity()).isEqualByComparingTo("3");
        assertThat(cementShortage.tolerancePercent()).isEqualByComparingTo("2.5");
        Discrepancy sandShortage = opened.stream().filter(d -> d.material().materialId().equals(sand)).findFirst()
                .orElseThrow();
        assertThat(sandShortage.material().sku()).isEqualTo("SIN-SKU");
        verify(outbox, times(2)).append(eq(organization), eq(luis), eq("DiscrepancyOpened"), eq("DISCREPANCY"), any(),
                any(DiscrepancyEvents.DiscrepancyChanged.class));
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
                                TestEvents.map("receptionLineId", UUID.randomUUID(), "materialId", sand,
                                        "dispatched", "n/d", "received", 1, "withinTolerance", false))));
        assertThat(handle(noFlag)).isEqualTo(2);
        Discrepancy fromNoFlag = TenantContext.callAs(user("ADMINISTRATOR", ana), () -> queries.list(null, null, null))
                .stream().filter(d -> d.material().materialId().equals(cement)).findFirst().orElseThrow();
        assertThat(fromNoFlag.material().name()).isEqualTo("Cemento Portland");
        assertThat(fromNoFlag.openedBy()).isEqualTo(TenantContext.SYSTEM_USER_ID);
        verify(outbox, times(2)).append(eq(organization), isNull(), eq("DiscrepancyOpened"), any(), any(), any());
    }

    @Test
    void unknown_events_and_incomplete_copies_are_ignored() throws Exception {
        assertThat(handle(events.envelope(UUID.randomUUID(), "OrderApproved", "ORDER", "o", null,
                TestEvents.map("orderId", UUID.randomUUID())))).isZero();
        handle(events.envelope(UUID.randomUUID(), "MaterialUpdated", "MATERIAL", "m", null, TestEvents.map("sku", "X")));
        handle(events.envelope(UUID.randomUUID(), "StaffAssigned", "ASSIGNMENT", "a", null, TestEvents.map()));
        handle(events.envelope(UUID.randomUUID(), "StaffUnassigned", "ASSIGNMENT", "a", null, TestEvents.map()));
        UUID assignment = UUID.randomUUID();
        handle(events.assigned(assignment, pedro, torreNorte, "WORKSITE"));
        handle(events.unassigned(assignment, pedro, torreNorte, "WORKSITE"));
        assertThat(store.assignedLocations(organization, pedro, "WORKSITE")).containsExactly(edificioSur);
        assertThat(store.materials).hasSize(1);
    }

    @Test
    void site_manager_registers_another_difference_of_a_reception_of_his_worksite() throws Exception {
        handle(events.receptionOpened(reception, luis));

        Discrepancy damage = TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.create(
                new DiscrepancyCommandService.CreateDiscrepancy(reception.receptionId(), cement,
                        DiscrepancyType.DAMAGE, new BigDecimal("2"), "Dos bolsas rotas")));

        assertThat(damage.status()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(damage.material().sku()).isEqualTo("CEM-001");
        assertThat(damage.tolerancePercent()).isEqualByComparingTo("2.5");
        assertThat(damage.shrinkagePercent()).isNull();
        assertThat(damage.openedBy()).isEqualTo(luis);

        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.create(
                new DiscrepancyCommandService.CreateDiscrepancy(reception.receptionId(), cement,
                        DiscrepancyType.DAMAGE, BigDecimal.ONE, "Otra igual")))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", pedro), () -> commands.create(
                new DiscrepancyCommandService.CreateDiscrepancy(reception.receptionId(), cement,
                        DiscrepancyType.DAMAGE, BigDecimal.ONE, "No es su obra")))).isInstanceOf(
                ResourceNotFoundException.class);
        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.create(
                new DiscrepancyCommandService.CreateDiscrepancy(reception.receptionId(), UUID.randomUUID(),
                        DiscrepancyType.DAMAGE, BigDecimal.ONE, "Material raro")))).isInstanceOf(
                ResourceNotFoundException.class).hasMessageContaining("material");
        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.create(
                new DiscrepancyCommandService.CreateDiscrepancy(UUID.randomUUID(), cement,
                        DiscrepancyType.DAMAGE, BigDecimal.ONE, "Recepción rara")))).isInstanceOf(
                ResourceNotFoundException.class).hasMessageContaining("recepción");
        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.create(
                new DiscrepancyCommandService.CreateDiscrepancy(null, cement, DiscrepancyType.DAMAGE, BigDecimal.ONE,
                        "Sin recepción")))).isInstanceOf(ValidationException.class);
    }

    @Test
    void manual_registration_after_confirmation_uses_the_line_data() throws Exception {
        handle(events.receptionConfirmed(UUID.randomUUID(), reception, luis,
                List.of(Line.of(rebar, "FIE-012", "Fierro 1/2", "100", "99", "2"))));

        Discrepancy wrong = TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.create(
                new DiscrepancyCommandService.CreateDiscrepancy(reception.receptionId(), rebar,
                        DiscrepancyType.WRONG_MATERIAL, new BigDecimal("5"), "Llegó fierro de 3/8")));

        assertThat(wrong.material().sku()).isEqualTo("FIE-012");
        assertThat(wrong.shrinkagePercent()).isEqualByComparingTo("1");
        assertThat(wrong.tolerancePercent()).isEqualByComparingTo("2");
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
        assertThat(responded.response().by()).isEqualTo(rosa);

        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", pedro),
                () -> commands.escalate(id, "No es su obra"))).isInstanceOf(ResourceNotFoundException.class);
        Discrepancy escalated = TenantContext.callAs(user("SITE_MANAGER", luis),
                () -> commands.escalate(id, "No llegó la reposición"));
        assertThat(escalated.status()).isEqualTo(DiscrepancyStatus.ESCALATED);
        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.accept(id)))
                .isInstanceOf(ConflictException.class);

        Discrepancy resolved = TenantContext.callAs(user("ADMINISTRATOR", ana),
                () -> commands.resolve(id, "Se descuenta al transportista"));
        assertThat(resolved.status()).isEqualTo(DiscrepancyStatus.RESOLVED);
        verify(outbox).append(eq(organization), eq(rosa), eq("DiscrepancyResponded"), eq("DISCREPANCY"), eq(id), any());
        verify(outbox).append(eq(organization), eq(luis), eq("DiscrepancyEscalated"), eq("DISCREPANCY"), eq(id), any());
        verify(outbox).append(eq(organization), eq(ana), eq("DiscrepancyResolved"), eq("DISCREPANCY"), eq(id), any());
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
        assertThat(TenantContext.callAs(user("SITE_MANAGER", luis), () -> queries.get(id)).status())
                .isEqualTo(DiscrepancyStatus.ACCEPTED);
        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", pedro), () -> queries.get(id)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> TenantContext.callAs(new TenantInfo(UUID.randomUUID(), ana, "ADMINISTRATOR"),
                () -> queries.get(id))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void evidence_photos_are_checked_stored_and_read_back() throws Exception {
        handle(events.receptionConfirmed(UUID.randomUUID(), reception, luis,
                List.of(Line.of(cement, "CEM-001", "Cemento Portland", "40", "37", "2.5"))));
        UUID id = store.byId.keySet().iterator().next();
        TenantInfo luisSession = user("SITE_MANAGER", luis);

        var evidence = TenantContext.callAs(luisSession, () -> commands.addEvidence(id,
                new DiscrepancyCommandService.EvidenceUpload("bolsas rotas.jpg", "image/jpeg", TestImages.JPEG)));
        assertThat(evidence.url()).isEqualTo("/api/v1/discrepancies/" + id + "/evidences/" + evidence.id() + "/content");
        assertThat(evidence.storageKey()).isEqualTo(organization + "/" + id + "/" + evidence.id() + ".jpg");
        assertThat(evidence.fileName()).isEqualTo("bolsas rotas.jpg");
        TenantContext.callAs(luisSession, () -> commands.addEvidence(id,
                new DiscrepancyCommandService.EvidenceUpload(null, "IMAGE/PNG", TestImages.PNG)));
        TenantContext.callAs(luisSession, () -> commands.addEvidence(id,
                new DiscrepancyCommandService.EvidenceUpload("a/b:c.webp", "image/webp", TestImages.WEBP)));

        Discrepancy withPhotos = TenantContext.callAs(luisSession, () -> queries.get(id));
        assertThat(withPhotos.evidences()).hasSize(3);
        assertThat(withPhotos.evidences().get(1).fileName()).isEqualTo("evidencia.png");
        assertThat(withPhotos.evidences().get(2).fileName()).isEqualTo("a_b_c.webp");
        var file = TenantContext.callAs(user("WAREHOUSE_MANAGER", rosa), () -> queries.evidence(id, evidence.id()));
        assertThat(file.content()).isEqualTo(TestImages.JPEG);
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
        byte[] huge = new byte[(int) DiscrepancyCommandService.MAX_EVIDENCE_BYTES + 1];
        System.arraycopy(TestImages.JPEG, 0, huge, 0, TestImages.JPEG.length);
        assertRejected(id, "image/jpeg", huge, "EVIDENCE_TOO_LARGE");
    }

    private void assertRejected(UUID id, String contentType, byte[] content, String code) {
        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", luis), () -> commands.addEvidence(id,
                new DiscrepancyCommandService.EvidenceUpload("x", contentType, content))))
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
                node.get("payload"));
        return TenantContext.callAs(new TenantInfo(organization, TenantContext.SYSTEM_USER_ID, "SYSTEM"),
                () -> handler.handle(event));
    }
}
