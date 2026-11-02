-- ERD ampliado por decisión del equipo: vuelve el registro manual de discrepancias (POST /discrepancies) que la web usa.
-- No se borra ni se renombra nada de lo ya guardado: las filas existentes quedan como AUTOMATIC y sin observación ni
-- porcentajes, porque V2 eliminó esos datos.

-- ---------- discrepancies ----------
ALTER TABLE discrepancies
    ADD COLUMN observation   VARCHAR(500),
    ADD COLUMN origin        VARCHAR(10) NOT NULL DEFAULT 'AUTOMATIC',
    ADD COLUMN shrinkage_pct NUMERIC(7, 2),
    ADD COLUMN tolerance_pct NUMERIC(5, 2);
ALTER TABLE discrepancies ALTER COLUMN origin DROP DEFAULT;

-- Automáticas: SHORTAGE, SURPLUS y SHRINKAGE. Manuales: las del formulario de la web (SHORTAGE, DAMAGE, EXCESS y
-- WRONG_MATERIAL).
ALTER TABLE discrepancies
    DROP CONSTRAINT ck_discrepancies_type,
    ADD CONSTRAINT ck_discrepancies_type CHECK (type IN ('SHORTAGE', 'SURPLUS', 'SHRINKAGE', 'DAMAGE', 'EXCESS',
        'WRONG_MATERIAL')),
    ADD CONSTRAINT ck_discrepancies_origin CHECK (origin IN ('AUTOMATIC', 'MANUAL'));

-- Solo una discrepancia manual puede no tener línea de recepción.
ALTER TABLE discrepancies
    DROP CONSTRAINT uk_discrepancies_reception_line,
    ALTER COLUMN reception_line_id DROP NOT NULL,
    ADD CONSTRAINT ck_discrepancies_reception_line CHECK (origin = 'MANUAL' OR reception_line_id IS NOT NULL);

-- QAS10: una discrepancia por línea de recepción, aunque ReceptionConfirmed llegue más de una vez.
CREATE UNIQUE INDEX uk_discrepancies_reception_line ON discrepancies (reception_line_id)
    WHERE reception_line_id IS NOT NULL;
-- A lo más una discrepancia sin cerrar por recepción, material y tipo. ordering no repite un material dentro de una
-- recepción (uk_order_lines_material y uk_dispatch_lines_order_line), así que las filas existentes no chocan.
CREATE UNIQUE INDEX uk_discrepancies_open ON discrepancies (reception_id, material_id, type)
    WHERE status IN ('OPEN', 'RESPONDED', 'ESCALATED');

-- ---------- Copias locales de ordering ----------
-- ReceptionOpened y ReceptionConfirmed las alimentan: validan y completan un registro manual sin llamar por REST.
CREATE TABLE reception_projection (
    reception_id    UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    dispatch_id     UUID NOT NULL,
    manifest_code   VARCHAR(40),
    worksite_id     UUID NOT NULL,
    warehouse_id    UUID NOT NULL
);

CREATE TABLE reception_line_projection (
    reception_line_id UUID PRIMARY KEY,
    reception_id      UUID NOT NULL REFERENCES reception_projection (reception_id),
    material_id       UUID NOT NULL,
    sku               VARCHAR(40),
    material_name     VARCHAR(150),
    shrinkage_percent NUMERIC(7, 2),
    tolerance_percent NUMERIC(5, 2)
);

CREATE INDEX idx_reception_line_projection_material ON reception_line_projection (reception_id, material_id);
