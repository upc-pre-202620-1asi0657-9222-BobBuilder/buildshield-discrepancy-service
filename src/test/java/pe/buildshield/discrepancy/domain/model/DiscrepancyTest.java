package pe.buildshield.discrepancy.domain.model;

import org.junit.jupiter.api.Test;
import pe.buildshield.discrepancy.shared.error.ConflictException;
import pe.buildshield.discrepancy.shared.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscrepancyTest {

    private static final Instant NOW = Instant.parse("2026-11-03T16:10:00Z");
    private static final UUID LUIS = UUID.randomUUID();
    private static final UUID ROSA = UUID.randomUUID();
    private static final UUID ANA = UUID.randomUUID();

    private final Discrepancy.ReceptionLink reception = new Discrepancy.ReceptionLink(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "MAN-0001", UUID.randomUUID(), UUID.randomUUID());
    private final Discrepancy.MaterialLink cement = new Discrepancy.MaterialLink(UUID.randomUUID(), "CEM-001",
            "Cemento", "BOLSA");

    @Test
    void opening_classifies_shrinkage_shortage_and_surplus_for_the_absolute_difference() {
        Discrepancy shrinkage = Discrepancy.open(reception, cement, new BigDecimal("40"), new BigDecimal("37"),
                new BigDecimal("7.50"), new BigDecimal("2.50"), LUIS, NOW);
        assertThat(shrinkage.type()).isEqualTo(DiscrepancyType.SHRINKAGE);
        assertThat(shrinkage.quantity()).isEqualByComparingTo("3");
        assertThat(shrinkage.status()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(shrinkage.reason()).isEqualTo("Merma de 7.5 % sobre 2.5 % tolerado");
        assertThat(shrinkage.openedBy()).isEqualTo(LUIS);
        assertThat(shrinkage.openedAt()).isEqualTo(NOW);
        assertThat(shrinkage.id()).isNull();
        assertThat(shrinkage.escalatedAt()).isNull();
        assertThat(shrinkage.resolvedAt()).isNull();
        assertThat(shrinkage.responses()).isEmpty();

        Discrepancy shrinkageWithoutPercent = Discrepancy.open(reception, cement, new BigDecimal("40"),
                new BigDecimal("37"), null, new BigDecimal("2.5"), LUIS, NOW);
        assertThat(shrinkageWithoutPercent.reason()).isEqualTo("Merma de 3 BOLSA sobre 2.5 % tolerado");

        Discrepancy shortage = Discrepancy.open(reception, cement, new BigDecimal("40"), new BigDecimal("37"),
                new BigDecimal("7.5"), BigDecimal.ZERO, LUIS, NOW);
        assertThat(shortage.type()).isEqualTo(DiscrepancyType.SHORTAGE);
        assertThat(shortage.reason()).isEqualTo("Faltan 3 BOLSA de 40 despachados");
        assertThat(Discrepancy.open(reception, new Discrepancy.MaterialLink(cement.materialId(), null, null, " "),
                BigDecimal.TEN, BigDecimal.ONE, null, null, LUIS, NOW).reason()).isEqualTo("Faltan 9 de 10 despachados");

        Discrepancy surplus = Discrepancy.open(reception, cement, new BigDecimal("10"), new BigDecimal("12"), null,
                new BigDecimal("5"), LUIS, NOW);
        assertThat(surplus.type()).isEqualTo(DiscrepancyType.SURPLUS);
        assertThat(surplus.quantity()).isEqualByComparingTo("2");
        assertThat(surplus.reason()).isEqualTo("Sobran 2 BOLSA de 10 despachados");

        assertThatThrownBy(() -> Discrepancy.open(reception, cement, BigDecimal.TEN, BigDecimal.TEN, null,
                null, LUIS, NOW)).isInstanceOf(ValidationException.class);
    }

    @Test
    void the_reason_never_exceeds_the_60_characters_of_the_er() {
        Discrepancy long_ = Discrepancy.open(reception, cement, new BigDecimal("123456789012.123"),
                new BigDecimal("1.001"), null, BigDecimal.ZERO, LUIS, NOW);
        assertThat(long_.reason()).hasSize(Texts.MAX_REASON).endsWith("…");
        assertThat(Texts.reason("  corto  ")).isEqualTo("corto");
    }

    @Test
    void responded_then_accepted_closes_the_discrepancy() {
        Discrepancy discrepancy = open();

        discrepancy.respond("Se repone mañana", ROSA, NOW.plusSeconds(60));
        assertThat(discrepancy.status()).isEqualTo(DiscrepancyStatus.RESPONDED);
        Response response = discrepancy.response().orElseThrow();
        assertThat(response.decision()).isEqualTo(Response.Decision.RESPOND);
        assertThat(response.authorId()).isEqualTo(ROSA);
        assertThat(response.comment()).isEqualTo("Se repone mañana");
        assertThat(response.createdAt()).isEqualTo(NOW.plusSeconds(60));

        discrepancy.accept(LUIS, NOW.plusSeconds(120));
        assertThat(discrepancy.status()).isEqualTo(DiscrepancyStatus.ACCEPTED);
        assertThat(discrepancy.status().closed()).isTrue();
        assertThat(discrepancy.responses()).extracting(Response::decision, Response::authorId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Response.Decision.RESPOND, ROSA),
                        org.assertj.core.groups.Tuple.tuple(Response.Decision.ACCEPT, LUIS));
        assertThat(discrepancy.escalation()).isEmpty();
        assertThat(discrepancy.resolution()).isEmpty();
        assertThatThrownBy(() -> discrepancy.responses().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void responded_then_escalated_then_resolved_closes_with_a_decision() {
        Discrepancy discrepancy = open();
        discrepancy.respond("Se repone mañana", ROSA, NOW);
        discrepancy.escalate("No llegó la reposición", LUIS, NOW.plusSeconds(10));
        assertThat(discrepancy.status()).isEqualTo(DiscrepancyStatus.ESCALATED);
        assertThat(discrepancy.escalation().orElseThrow().comment()).isEqualTo("No llegó la reposición");
        assertThat(discrepancy.escalatedAt()).isEqualTo(NOW.plusSeconds(10));

        discrepancy.resolve("Se descuenta al transportista", ANA, NOW.plusSeconds(20));
        assertThat(discrepancy.status()).isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(discrepancy.resolvedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(discrepancy.resolution()).contains(new Resolution("Se descuenta al transportista", ANA,
                NOW.plusSeconds(20)));
    }

    @Test
    void invalid_transitions_are_conflicts() {
        Discrepancy discrepancy = open();
        assertThatThrownBy(() -> discrepancy.accept(LUIS, NOW)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("abierta");
        assertThatThrownBy(() -> discrepancy.escalate("Motivo", LUIS, NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> discrepancy.resolve("Decisión", ANA, NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> discrepancy.respond("ab", ROSA, NOW)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("text");
        assertThatThrownBy(() -> discrepancy.respond("x".repeat(501), ROSA, NOW))
                .isInstanceOf(ValidationException.class);
        discrepancy.respond("Respuesta", ROSA, NOW);
        assertThatThrownBy(() -> discrepancy.respond("Otra", ROSA, NOW)).isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getCode())
                        .isEqualTo(Discrepancy.INVALID_TRANSITION));
    }

    @Test
    void evidences_are_only_added_while_the_discrepancy_is_not_closed() {
        Discrepancy discrepancy = open();
        Evidence photo = new Evidence(UUID.randomUUID(), "org/disc/foto.JPG", NOW);
        discrepancy.addEvidence(photo);
        assertThat(discrepancy.evidences()).containsExactly(photo);
        assertThatThrownBy(() -> discrepancy.evidences().add(photo)).isInstanceOf(UnsupportedOperationException.class);

        discrepancy.respond("Respuesta", ROSA, NOW);
        discrepancy.accept(LUIS, NOW);
        assertThatThrownBy(() -> discrepancy.addEvidence(photo)).isInstanceOf(ConflictException.class);
    }

    @Test
    void evidence_derives_type_and_name_from_its_key() {
        assertThat(new Evidence(UUID.randomUUID(), "o/d/e.jpg", NOW).contentType()).isEqualTo("image/jpeg");
        assertThat(new Evidence(UUID.randomUUID(), "o/d/e.webp", NOW).fileName()).isEqualTo("e.webp");
        assertThat(new Evidence(UUID.randomUUID(), "sin-extension", NOW).contentType())
                .isEqualTo("application/octet-stream");
        assertThat(new Evidence(UUID.randomUUID(), "sin-extension", NOW).fileName()).isEqualTo("sin-extension");
        assertThatThrownBy(() -> new Evidence(UUID.randomUUID(), "x".repeat(256), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void links_fill_missing_references_and_restore_keeps_everything() {
        UUID dispatch = UUID.fromString("6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c");
        UUID material = UUID.fromString("4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a");
        var link = new Discrepancy.ReceptionLink(UUID.randomUUID(), UUID.randomUUID(), dispatch, " ",
                UUID.randomUUID(), UUID.randomUUID());
        var unnamed = new Discrepancy.MaterialLink(material, null, " ", null);
        assertThat(link.manifestCode()).isEqualTo("6f7a8b9c");
        assertThat(unnamed.sku()).isEqualTo("SIN-SKU");
        assertThat(unnamed.name()).isEqualTo("Material 4d5e6f7a");
        assertThat(unnamed.unit()).isNull();

        UUID id = UUID.randomUUID();
        Response later = new Response(UUID.randomUUID(), Response.Decision.RESOLVE, ANA, "Decisión", NOW.plusSeconds(5));
        Response earlier = new Response(UUID.randomUUID(), Response.Decision.RESPOND, ROSA, "Texto", NOW);
        Discrepancy restored = Discrepancy.restore(id, link, unnamed, DiscrepancyType.SURPLUS, BigDecimal.ONE,
                "Sobran 1", DiscrepancyStatus.RESOLVED, LUIS, NOW, NOW, NOW.plusSeconds(5), List.of(later, earlier),
                null, 4L);
        assertThat(restored.id()).isEqualTo(id);
        assertThat(restored.version()).isEqualTo(4L);
        assertThat(restored.responses()).containsExactly(earlier, later);
        assertThat(restored.evidences()).isEmpty();
        assertThat(Discrepancy.restore(id, link, unnamed, DiscrepancyType.SURPLUS, BigDecimal.ONE, "Sobran 1",
                DiscrepancyStatus.OPEN, LUIS, NOW, null, null, null, List.of(), 0L).responses()).isEmpty();
        assertThat(DiscrepancyType.SURPLUS.label()).isEqualTo("Sobrante");
        assertThat(DiscrepancyStatus.RESOLVED.label()).isEqualTo("Resuelta");
        assertThat(DiscrepancyStatus.ESCALATED.closed()).isFalse();
        assertThatThrownBy(() -> new Response(UUID.randomUUID(), Response.Decision.RESPOND, ROSA, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Resolution(null, ANA, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Discrepancy.ReceptionLink(UUID.randomUUID(), null, dispatch, null,
                UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Discrepancy.restore(null, link, unnamed, DiscrepancyType.SURPLUS, BigDecimal.ONE,
                "x", DiscrepancyStatus.OPEN, LUIS, NOW, null, null, null, null, 0L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void filter_knows_when_the_scope_is_empty() {
        assertThat(new DiscrepancyFilter(null, null, null, null, null).seesNothing()).isFalse();
        assertThat(new DiscrepancyFilter(null, null, null, java.util.Set.of(), null).seesNothing()).isTrue();
        assertThat(new DiscrepancyFilter(null, null, null, null, java.util.Set.of()).seesNothing()).isTrue();
    }

    private Discrepancy open() {
        return Discrepancy.open(reception, cement, new BigDecimal("40"), new BigDecimal("37"), null, null, LUIS, NOW);
    }
}
