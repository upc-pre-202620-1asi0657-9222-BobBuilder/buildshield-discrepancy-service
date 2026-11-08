package pe.buildshield.discrepancy.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.buildshield.discrepancy.domain.model.ReceptionProjections;
import pe.buildshield.discrepancy.domain.model.ReceptionSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code reception_projection} y {@code reception_line_projection} con JDBC; toda consulta filtra por la organización. */
@Repository
class JdbcReceptionProjections implements ReceptionProjections {

    private final JdbcTemplate jdbc;

    JdbcReceptionProjections(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(UUID organizationId, ReceptionSnapshot reception) {
        jdbc.update("""
                INSERT INTO reception_projection (reception_id, organization_id, dispatch_id, manifest_code, worksite_id,
                    warehouse_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (reception_id) DO UPDATE SET
                    manifest_code = COALESCE(EXCLUDED.manifest_code, reception_projection.manifest_code)
                """, reception.receptionId(), organizationId, reception.dispatchId(), reception.manifestCode(),
                reception.worksiteId(), reception.warehouseId());
        for (ReceptionSnapshot.Line line : reception.lines()) {
            jdbc.update("""
                    INSERT INTO reception_line_projection (reception_line_id, reception_id, material_id, sku,
                        material_name, shrinkage_percent, tolerance_percent)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (reception_line_id) DO UPDATE SET
                        sku = COALESCE(EXCLUDED.sku, reception_line_projection.sku),
                        material_name = COALESCE(EXCLUDED.material_name, reception_line_projection.material_name),
                        shrinkage_percent = COALESCE(EXCLUDED.shrinkage_percent,
                            reception_line_projection.shrinkage_percent),
                        tolerance_percent = COALESCE(EXCLUDED.tolerance_percent,
                            reception_line_projection.tolerance_percent)
                    """, line.receptionLineId(), reception.receptionId(), line.materialId(), line.sku(),
                    line.materialName(), line.shrinkagePercent(), line.tolerancePercent());
        }
    }

    @Override
    public Optional<ReceptionSnapshot> find(UUID organizationId, UUID receptionId) {
        List<ReceptionSnapshot.Line> lines = jdbc.query("""
                SELECT l.* FROM reception_line_projection l
                JOIN reception_projection r ON r.reception_id = l.reception_id
                WHERE r.organization_id = ? AND l.reception_id = ?
                """, (rs, row) -> new ReceptionSnapshot.Line(rs.getObject("reception_line_id", UUID.class),
                rs.getObject("material_id", UUID.class), rs.getString("sku"), rs.getString("material_name"),
                rs.getBigDecimal("shrinkage_percent"), rs.getBigDecimal("tolerance_percent")), organizationId,
                receptionId);
        return jdbc.query("""
                SELECT * FROM reception_projection WHERE organization_id = ? AND reception_id = ?
                """, (rs, row) -> new ReceptionSnapshot(rs.getObject("reception_id", UUID.class),
                rs.getObject("dispatch_id", UUID.class), rs.getString("manifest_code"),
                rs.getObject("worksite_id", UUID.class), rs.getObject("warehouse_id", UUID.class), lines),
                organizationId, receptionId).stream().findFirst();
    }
}
