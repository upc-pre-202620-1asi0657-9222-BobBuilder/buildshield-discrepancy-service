package pe.buildshield.discrepancy.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.buildshield.discrepancy.support.DiscrepancyIntegrationTest;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migración V4 contra PostgreSQL real, en un esquema aparte: lleva una base con filas de V3 a V4 sin perderlas y fija
 * las columnas, los tipos, el check de tipos y los índices parciales del ERD ampliado.
 */
@DiscrepancyIntegrationTest
class ManualDiscrepancySchemaIT {

    private static final String SCHEMA = "v4_migration_it";
    private static final UUID USER = UUID.randomUUID();

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID stored = UUID.randomUUID();
    private final UUID storedLine = UUID.randomUUID();

    @BeforeEach
    void migrateWithExistingRows() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        migrate("3");
        jdbc.update("INSERT INTO " + SCHEMA + """
                .discrepancies (id, organization_id, reception_id, reception_line_id, dispatch_id, manifest_code,
                    worksite_id, warehouse_id, material_id, sku, material_name, unit, type, quantity, reason, status,
                    version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 'MAN-1', ?, ?, ?, 'CEM-001', 'Cemento', 'BAG', 'SHRINKAGE', 3, 'Merma', 'OPEN',
                    0, now(), ?, now(), ?)
                """, stored, UUID.randomUUID(), UUID.randomUUID(), storedLine, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), USER, USER);
        migrate(null);
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void existing_rows_stay_as_they_were_and_become_automatic() {
        assertThat(jdbc.queryForMap("SELECT type, reason, reception_line_id, origin, observation, shrinkage_pct, "
                + "tolerance_pct FROM " + SCHEMA + ".discrepancies WHERE id = ?", stored))
                .containsEntry("type", "SHRINKAGE").containsEntry("reason", "Merma")
                .containsEntry("reception_line_id", storedLine).containsEntry("origin", "AUTOMATIC")
                .containsEntry("observation", null).containsEntry("shrinkage_pct", null)
                .containsEntry("tolerance_pct", null);
    }

    @Test
    void the_columns_have_the_types_of_the_expanded_erd() {
        assertThat(columns("discrepancies"))
                .containsEntry("observation", "character varying(500) null")
                .containsEntry("origin", "character varying(10) not null")
                .containsEntry("shrinkage_pct", "numeric(7,2) null")
                .containsEntry("tolerance_pct", "numeric(5,2) null")
                .containsEntry("reception_line_id", "uuid null")
                .containsEntry("reason", "character varying(60) not null");
        assertThat(columns("reception_projection")).containsOnly(
                Map.entry("reception_id", "uuid not null"),
                Map.entry("organization_id", "uuid not null"),
                Map.entry("dispatch_id", "uuid not null"),
                Map.entry("manifest_code", "character varying(40) null"),
                Map.entry("worksite_id", "uuid not null"),
                Map.entry("warehouse_id", "uuid not null"));
        assertThat(columns("reception_line_projection")).containsOnly(
                Map.entry("reception_line_id", "uuid not null"),
                Map.entry("reception_id", "uuid not null"),
                Map.entry("material_id", "uuid not null"),
                Map.entry("sku", "character varying(40) null"),
                Map.entry("material_name", "character varying(150) null"),
                Map.entry("shrinkage_percent", "numeric(7,2) null"),
                Map.entry("tolerance_percent", "numeric(5,2) null"));
    }

    @Test
    void only_manual_discrepancies_may_lack_a_line_and_a_line_stays_unique() {
        UUID reception = UUID.randomUUID();
        UUID material = UUID.randomUUID();
        insert(reception, material, null, "DAMAGE", "MANUAL", "OPEN");
        insert(reception, material, null, "WRONG_MATERIAL", "MANUAL", "OPEN");

        assertThatThrownBy(() -> insert(reception, UUID.randomUUID(), null, "SHORTAGE", "AUTOMATIC", "OPEN"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_discrepancies_reception_line");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), UUID.randomUUID(), storedLine, "SHORTAGE", "AUTOMATIC",
                "OPEN")).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_discrepancies_reception_line");
    }

    @Test
    void one_open_discrepancy_per_reception_material_and_type() {
        UUID reception = UUID.randomUUID();
        UUID material = UUID.randomUUID();
        insert(reception, material, null, "DAMAGE", "MANUAL", "RESPONDED");

        assertThatThrownBy(() -> insert(reception, material, null, "DAMAGE", "MANUAL", "OPEN"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_discrepancies_open");
        insert(reception, material, null, "EXCESS", "MANUAL", "OPEN");
        insert(reception, material, null, "DAMAGE", "MANUAL", "ACCEPTED");
        insert(reception, material, null, "DAMAGE", "MANUAL", "ACCEPTED");
    }

    @Test
    void the_check_accepts_the_union_of_types_and_origins_only() {
        for (String type : new String[]{"SHORTAGE", "SURPLUS", "SHRINKAGE", "DAMAGE", "EXCESS", "WRONG_MATERIAL"}) {
            insert(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type, "AUTOMATIC", "OPEN");
        }
        assertThatThrownBy(() -> insert(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OTHER", "AUTOMATIC",
                "OPEN")).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_discrepancies_type");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DAMAGE", "OTHER",
                "OPEN")).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_discrepancies_origin");
    }

    @Test
    void a_line_of_the_copy_needs_its_reception() {
        UUID reception = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".reception_line_projection (reception_line_id, "
                + "reception_id, material_id) VALUES (?, ?, ?)", UUID.randomUUID(), reception, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void migrate(String target) {
        var configuration = Flyway.configure().dataSource(dataSource).schemas(SCHEMA)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void insert(UUID reception, UUID material, UUID line, String type, String origin, String status) {
        jdbc.update("INSERT INTO " + SCHEMA + """
                .discrepancies (id, organization_id, reception_id, reception_line_id, dispatch_id, manifest_code,
                    worksite_id, warehouse_id, material_id, sku, material_name, type, quantity, reason, origin, status,
                    version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 'MAN-1', ?, ?, ?, 'CEM-001', 'Cemento', ?, 1, 'Motivo', ?, ?, 0, now(), ?, now(), ?)
                """, UUID.randomUUID(), UUID.randomUUID(), reception, line, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), material, type, origin, status, USER, USER);
    }

    private Map<String, String> columns(String table) {
        Map<String, String> found = new LinkedHashMap<>();
        jdbc.query("""
                SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable
                FROM information_schema.columns WHERE table_schema = ? AND table_name = ?
                """, rs -> {
            String type = rs.getString("data_type");
            if (type.equals("character varying")) {
                type += "(" + rs.getInt("character_maximum_length") + ")";
            } else if (type.equals("numeric")) {
                type += "(" + rs.getInt("numeric_precision") + "," + rs.getInt("numeric_scale") + ")";
            }
            found.put(rs.getString("column_name"), type + (rs.getString("is_nullable").equals("YES") ? " null"
                    : " not null"));
        }, SCHEMA, table);
        return found;
    }
}
