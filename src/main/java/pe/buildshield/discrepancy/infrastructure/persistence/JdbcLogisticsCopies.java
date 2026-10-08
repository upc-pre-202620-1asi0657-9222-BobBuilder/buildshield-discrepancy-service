package pe.buildshield.discrepancy.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.buildshield.discrepancy.domain.model.LogisticsCopies;
import pe.buildshield.discrepancy.domain.model.MaterialSnapshot;
import pe.buildshield.discrepancy.domain.model.ReceptionSnapshot;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Copias locales con JDBC; toda consulta filtra por la organización. */
@Repository
class JdbcLogisticsCopies implements LogisticsCopies {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcLogisticsCopies(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void upsertMaterial(UUID organizationId, MaterialSnapshot material, boolean active) {
        jdbc.update("""
                INSERT INTO material_projection (material_id, organization_id, sku, name, tolerance_percent, active,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (material_id) DO UPDATE SET sku = EXCLUDED.sku, name = EXCLUDED.name,
                    tolerance_percent = EXCLUDED.tolerance_percent, active = EXCLUDED.active,
                    updated_at = EXCLUDED.updated_at
                """, material.materialId(), organizationId, material.sku(), material.name(),
                material.tolerancePercent(), active, now());
    }

    @Override
    public Optional<MaterialSnapshot> material(UUID organizationId, UUID materialId) {
        return jdbc.query("""
                SELECT material_id, sku, name, tolerance_percent FROM material_projection
                WHERE organization_id = ? AND material_id = ?
                """, (rs, row) -> new MaterialSnapshot(rs.getObject("material_id", UUID.class), rs.getString("sku"),
                rs.getString("name"), rs.getBigDecimal("tolerance_percent")), organizationId, materialId)
                .stream().findFirst();
    }

    @Override
    public void assign(UUID organizationId, UUID assignmentId, UUID userId, UUID locationId, String locationType) {
        jdbc.update("""
                INSERT INTO assignment_projection (assignment_id, organization_id, user_id, location_id, location_type,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (assignment_id) DO UPDATE SET user_id = EXCLUDED.user_id,
                    location_id = EXCLUDED.location_id, location_type = EXCLUDED.location_type,
                    updated_at = EXCLUDED.updated_at
                """, assignmentId, organizationId, userId, locationId, locationType, now());
    }

    @Override
    public void unassign(UUID assignmentId) {
        jdbc.update("DELETE FROM assignment_projection WHERE assignment_id = ?", assignmentId);
    }

    @Override
    public Set<UUID> assignedLocations(UUID organizationId, UUID userId, String locationType) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT location_id FROM assignment_projection
                WHERE organization_id = ? AND user_id = ? AND location_type = ?
                """, UUID.class, organizationId, userId, locationType));
    }

    @Override
    public void upsertReception(UUID organizationId, ReceptionSnapshot reception) {
        jdbc.update("""
                INSERT INTO reception_projection (reception_id, organization_id, dispatch_id, manifest_code, worksite_id,
                    warehouse_id, confirmed, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reception_id) DO UPDATE SET
                    manifest_code = COALESCE(EXCLUDED.manifest_code, reception_projection.manifest_code),
                    confirmed = reception_projection.confirmed OR EXCLUDED.confirmed,
                    updated_at = EXCLUDED.updated_at
                """, reception.receptionId(), organizationId, reception.dispatchId(), reception.manifestCode(),
                reception.worksiteId(), reception.warehouseId(), reception.confirmed(), now());
        for (ReceptionSnapshot.Line line : reception.lines()) {
            jdbc.update("""
                    INSERT INTO reception_line_projection (reception_line_id, reception_id, material_id, sku,
                        material_name, dispatched, received, shrinkage_percent, tolerance_percent)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (reception_line_id) DO UPDATE SET received = EXCLUDED.received,
                        shrinkage_percent = EXCLUDED.shrinkage_percent, tolerance_percent = EXCLUDED.tolerance_percent
                    """, line.receptionLineId(), reception.receptionId(), line.materialId(), line.sku(),
                    line.materialName(), line.dispatched(), line.received(), line.shrinkagePercent(),
                    line.tolerancePercent());
        }
    }

    @Override
    public Optional<ReceptionSnapshot> reception(UUID organizationId, UUID receptionId) {
        List<ReceptionSnapshot.Line> lines = jdbc.query("""
                SELECT l.* FROM reception_line_projection l
                JOIN reception_projection r ON r.reception_id = l.reception_id
                WHERE r.organization_id = ? AND l.reception_id = ?
                ORDER BY l.sku NULLS LAST, l.reception_line_id
                """, (rs, row) -> new ReceptionSnapshot.Line(rs.getObject("reception_line_id", UUID.class),
                rs.getObject("material_id", UUID.class), rs.getString("sku"), rs.getString("material_name"),
                rs.getBigDecimal("dispatched"), rs.getBigDecimal("received"), rs.getBigDecimal("shrinkage_percent"),
                rs.getBigDecimal("tolerance_percent")), organizationId, receptionId);
        return jdbc.query("""
                SELECT * FROM reception_projection WHERE organization_id = ? AND reception_id = ?
                """, (rs, row) -> new ReceptionSnapshot(rs.getObject("reception_id", UUID.class),
                rs.getObject("dispatch_id", UUID.class), rs.getString("manifest_code"),
                rs.getObject("worksite_id", UUID.class), rs.getObject("warehouse_id", UUID.class),
                rs.getBoolean("confirmed"), lines), organizationId, receptionId).stream().findFirst();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
