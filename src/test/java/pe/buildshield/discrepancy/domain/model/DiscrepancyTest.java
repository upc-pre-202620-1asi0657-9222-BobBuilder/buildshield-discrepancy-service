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
            "Cemento");

    @Test
    void automatic_opening_is_a_shortage_or_an_excess_for_the_absolute_difference() {
        Discrepancy shortage = Discrepancy.openAutomatic(reception, cement, new BigDecimal("40"), new BigDecimal("37"),
                new BigDecimal("7.50"), new BigDecimal("2.50"), LUIS, NOW);

        assertThat(shortage.type()).isEqualTo(DiscrepancyType.SHORTAGE);
        assertThat(shortage.quantity()).isEqualByComparingTo("3");
        assertThat(shortage.status()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(shortage.origin()).isEqualTo(DiscrepancyOrigin.AUTOMATIC);
        assertThat(shortage.observation())
                .isEqualTo("Despachado 40, recibido 37: merma de 7.5 % sobre una tolerancia de 2.5 %");
        assertThat(shortage.openedBy()).isEqualTo(LUIS);
        assertThat(shortage.openedAt()).isEqualTo(NOW);
        assertThat(shortage.id()).isNull();

        Discrepancy excess = Discrepancy.openAutomatic(reception, cement, new BigDecimal("10"), new BigDecimal("12"),
                null, null, LUIS, NOW);
        assertThat(excess.type()).isEqualTo(DiscrepancyType.EXCESS);
        assertThat(excess.quantity()).isEqualByComparingTo("2");
        assertThat(excess.tolerancePercent()).isEqualByComparingTo("0");
        assertThat(excess.observation()).isEqualTo("Despachado 10, recibido 12");

        assertThatThrownBy(() -> Discrepancy.openAutomatic(reception, cement, BigDecimal.TEN, BigDecimal.TEN, null,
                null, LUIS, NOW)).isInstanceOf(ValidationException.class);
    }

    @Test
    void manual_opening_validates_type_quantity_and_observation() {
        Discrepancy damage = Discrepancy.openManual(reception, cement, DiscrepancyType.DAMAGE, new BigDecimal("2"),
                null, new BigDecimal("2.5"), "  Dos bolsas rotas  ", LUIS, NOW);
        assertThat(damage.observation()).isEqualTo("Dos bolsas rotas");
        assertThat(damage.origin()).isEqualTo(DiscrepancyOrigin.MANUAL);

        assertThatThrownBy(() -> Discrepancy.openManual(reception, cement, null, BigDecimal.ONE, null, null,
                "Texto válido", LUIS, NOW)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Discrepancy.openManual(reception, cement, DiscrepancyType.DAMAGE, BigDecimal.ZERO,
                null, null, "Texto válido", LUIS, NOW)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Discrepancy.openManual(reception, cement, DiscrepancyType.DAMAGE, null, null, null,
                "Texto válido", LUIS, NOW)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Discrepancy.openManual(reception, cement, DiscrepancyType.DAMAGE, BigDecimal.ONE,
                null, null, "ab", LUIS, NOW)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("observation");
        assertThatThrownBy(() -> Discrepancy.openManual(reception, cement, DiscrepancyType.DAMAGE, BigDecimal.ONE,
                null, null, "x".repeat(501), LUIS, NOW)).isInstanceOf(ValidationException.class);
    }

    @Test
    void responded_then_accepted_closes_the_discrepancy() {
        Discrepancy discrepancy = open();

        discrepancy.respond("Se repone mañana", ROSA, NOW.plusSeconds(60));
        assertThat(discrepancy.status()).isEqualTo(DiscrepancyStatus.RESPONDED);
        assertThat(discrepancy.response()).isEqualTo(new Note("Se repone mañana", ROSA, NOW.plusSeconds(60)));

        discrepancy.accept(LUIS, NOW.plusSeconds(120));
        assertThat(discrepancy.status()).isEqualTo(DiscrepancyStatus.ACCEPTED);
        assertThat(discrepancy.status().closed()).isTrue();
        assertThat(discrepancy.acceptance().by()).isEqualTo(LUIS);
    }

    @Test
    void responded_then_escalated_then_resolved_closes_with_a_decision() {
        Discrepancy discrepancy = open();
        discrepancy.respond("Se repone mañana", ROSA, NOW);
        discrepancy.escalate("No llegó la reposición", LUIS, NOW.plusSeconds(10));
        assertThat(discrepancy.status()).isEqualTo(DiscrepancyStatus.ESCALATED);
        assertThat(discrepancy.escalation().text()).isEqualTo("No llegó la reposición");

        discrepancy.resolve("Se descuenta al transportista", ANA, NOW.plusSeconds(20));
        assertThat(discrepancy.status()).isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(discrepancy.resolution().by()).isEqualTo(ANA);
    }

    @Test
    void invalid_transitions_are_conflicts() {
        Discrepancy discrepancy = open();
        assertThatThrownBy(() -> discrepancy.accept(LUIS, NOW)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("abierta");
        assertThatThrownBy(() -> discrepancy.escalate("Motivo", LUIS, NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> discrepancy.resolve("Decisión", ANA, NOW)).isInstanceOf(ConflictException.class);
        discrepancy.respond("Respuesta", ROSA, NOW);
        assertThatThrownBy(() -> discrepancy.respond("Otra", ROSA, NOW)).isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getCode())
                        .isEqualTo(Discrepancy.INVALID_TRANSITION));
        assertThatThrownBy(() -> discrepancy.respond("ab", ROSA, NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void evidences_are_only_added_while_the_discrepancy_is_not_closed() {
        Discrepancy discrepancy = open();
        Evidence photo = new Evidence(UUID.randomUUID(), "k", "/u", "foto.jpg", "image/jpeg", 10, LUIS, NOW);
        discrepancy.addEvidence(photo);
        assertThat(discrepancy.evidences()).containsExactly(photo);
        assertThatThrownBy(() -> discrepancy.evidences().add(photo)).isInstanceOf(UnsupportedOperationException.class);

        discrepancy.respond("Respuesta", ROSA, NOW);
        discrepancy.accept(LUIS, NOW);
        assertThatThrownBy(() -> discrepancy.addEvidence(photo)).isInstanceOf(ConflictException.class);
    }

    @Test
    void links_fill_missing_references_and_restore_keeps_everything() {
        UUID dispatch = UUID.fromString("6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c");
        UUID material = UUID.fromString("4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a");
        var link = new Discrepancy.ReceptionLink(UUID.randomUUID(), null, dispatch, " ", UUID.randomUUID(),
                UUID.randomUUID());
        var unnamed = new Discrepancy.MaterialLink(material, null, " ");
        assertThat(link.manifestCode()).isEqualTo("6f7a8b9c");
        assertThat(unnamed.sku()).isEqualTo("SIN-SKU");
        assertThat(unnamed.name()).isEqualTo("Material 4d5e6f7a");

        UUID id = UUID.randomUUID();
        Note note = new Note("Texto", ROSA, NOW);
        Discrepancy restored = Discrepancy.restore(id, link, unnamed, DiscrepancyType.WRONG_MATERIAL, BigDecimal.ONE,
                null, BigDecimal.ONE, "Obs", DiscrepancyOrigin.MANUAL, DiscrepancyStatus.RESOLVED, LUIS, NOW, note,
                note, note, null, List.of(), 4L);
        assertThat(restored.id()).isEqualTo(id);
        assertThat(restored.version()).isEqualTo(4L);
        assertThat(restored.shrinkagePercent()).isNull();
        assertThat(DiscrepancyType.WRONG_MATERIAL.label()).isEqualTo("Material equivocado");
        assertThat(DiscrepancyStatus.RESOLVED.label()).isEqualTo("Resuelta");
        assertThat(DiscrepancyStatus.ESCALATED.closed()).isFalse();
        assertThatThrownBy(() -> new Note(null, ROSA, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Discrepancy.ReceptionLink(null, null, dispatch, null, UUID.randomUUID(),
                UUID.randomUUID())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void filter_knows_when_the_scope_is_empty() {
        assertThat(new DiscrepancyFilter(null, null, null, null, null).seesNothing()).isFalse();
        assertThat(new DiscrepancyFilter(null, null, null, java.util.Set.of(), null).seesNothing()).isTrue();
        assertThat(new DiscrepancyFilter(null, null, null, null, java.util.Set.of()).seesNothing()).isTrue();
    }

    private Discrepancy open() {
        return Discrepancy.openAutomatic(reception, cement, new BigDecimal("40"), new BigDecimal("37"), null, null,
                LUIS, NOW);
    }
}
