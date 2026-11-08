-- Gestión de Discrepancias (discrepancy_db), portado de V21 del Core (stash del Sprint 3, US36). Diferencias
-- detectadas en la obra, con evidencia, respuesta del almacén y decisión final. reception_id, dispatch_id,
-- material_id y los lugares son de otros servicios: sin llave foránea.
CREATE TABLE discrepancies (
    id                UUID PRIMARY KEY,
    organization_id   UUID           NOT NULL,
    reception_id      UUID           NOT NULL,
    reception_line_id UUID,
    dispatch_id       UUID           NOT NULL,
    manifest_code     VARCHAR(40)    NOT NULL,
    worksite_id       UUID           NOT NULL,
    warehouse_id      UUID           NOT NULL,
    material_id       UUID           NOT NULL,
    sku               VARCHAR(40)    NOT NULL,
    material_name     VARCHAR(150)   NOT NULL,
    type              VARCHAR(20)    NOT NULL,
    quantity          NUMERIC(14, 3) NOT NULL,
    shrinkage_pct     NUMERIC(7, 2),
    tolerance_pct     NUMERIC(5, 2)  NOT NULL,
    observation       VARCHAR(500)   NOT NULL,
    origin            VARCHAR(10)    NOT NULL,
    status            VARCHAR(15)    NOT NULL,
    opened_by         UUID           NOT NULL,
    opened_at         TIMESTAMPTZ    NOT NULL,
    response_text     VARCHAR(500),
    responded_by      UUID,
    responded_at      TIMESTAMPTZ,
    escalation_reason VARCHAR(500),
    escalated_by      UUID,
    escalated_at      TIMESTAMPTZ,
    resolution        VARCHAR(500),
    resolved_by       UUID,
    resolved_at       TIMESTAMPTZ,
    accepted_by       UUID,
    accepted_at       TIMESTAMPTZ,
    version           BIGINT         NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL,
    created_by        UUID           NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    updated_by        UUID           NOT NULL,
    CONSTRAINT ck_discrepancies_type CHECK (type IN ('SHORTAGE', 'DAMAGE', 'EXCESS', 'WRONG_MATERIAL')),
    CONSTRAINT ck_discrepancies_origin CHECK (origin IN ('AUTOMATIC', 'MANUAL')),
    CONSTRAINT ck_discrepancies_status CHECK (status IN ('OPEN', 'RESPONDED', 'ACCEPTED', 'ESCALATED', 'RESOLVED')),
    CONSTRAINT ck_discrepancies_quantity CHECK (quantity > 0),
    CONSTRAINT ck_discrepancies_response CHECK (status = 'OPEN'
        OR (response_text IS NOT NULL AND responded_by IS NOT NULL AND responded_at IS NOT NULL)),
    CONSTRAINT ck_discrepancies_escalation CHECK (status NOT IN ('ESCALATED', 'RESOLVED')
        OR (escalation_reason IS NOT NULL AND escalated_by IS NOT NULL AND escalated_at IS NOT NULL)),
    CONSTRAINT ck_discrepancies_resolution CHECK (status <> 'RESOLVED'
        OR (resolution IS NOT NULL AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL))
);

-- QAS10: una discrepancia automática por línea de recepción, aunque ReceptionConfirmed llegue más de una vez.
CREATE UNIQUE INDEX uk_discrepancies_reception_line ON discrepancies (reception_line_id)
    WHERE reception_line_id IS NOT NULL;
-- A lo más una discrepancia sin cerrar por recepción, material y tipo.
CREATE UNIQUE INDEX uk_discrepancies_open ON discrepancies (reception_id, material_id, type)
    WHERE status IN ('OPEN', 'RESPONDED', 'ESCALATED');
CREATE INDEX idx_discrepancies_worksite ON discrepancies (organization_id, worksite_id);
CREATE INDEX idx_discrepancies_warehouse ON discrepancies (organization_id, warehouse_id);
CREATE INDEX idx_discrepancies_dispatch ON discrepancies (organization_id, dispatch_id);

-- Fotos de evidencia: el archivo lo guarda EvidenceStorageAdapter; aquí solo su referencia.
CREATE TABLE discrepancy_evidences (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    discrepancy_id  UUID         NOT NULL REFERENCES discrepancies (id),
    storage_key     VARCHAR(300) NOT NULL,
    url             VARCHAR(500) NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    content_type    VARCHAR(50)  NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    uploaded_by     UUID         NOT NULL,
    uploaded_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_discrepancy_evidences_size CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    CONSTRAINT ck_discrepancy_evidences_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp'))
);

CREATE INDEX idx_discrepancy_evidences_discrepancy ON discrepancy_evidences (organization_id, discrepancy_id);

-- Copia de materiales de organization (MaterialRegistered, MaterialUpdated).
CREATE TABLE material_projection (
    material_id       UUID PRIMARY KEY,
    organization_id   UUID          NOT NULL,
    sku               VARCHAR(40)   NOT NULL,
    name              VARCHAR(150)  NOT NULL,
    unit              VARCHAR(10),
    tolerance_percent NUMERIC(5, 2) NOT NULL,
    active            BOOLEAN       NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL
);

-- Copia de asignaciones de organization (StaffAssigned, StaffUnassigned): qué ve cada encargado.
CREATE TABLE assignment_projection (
    assignment_id   UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    location_id     UUID        NOT NULL,
    location_type   VARCHAR(20) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_assignment_projection_user ON assignment_projection (organization_id, user_id, location_type);

-- Datos de la recepción que traen los eventos de ordering (ReceptionOpened, ReceptionPartiallyReceived,
-- ReceptionConfirmed): permiten registrar a mano otras diferencias de esa recepción.
CREATE TABLE reception_projection (
    reception_id    UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    dispatch_id     UUID        NOT NULL,
    manifest_code   VARCHAR(40),
    worksite_id     UUID        NOT NULL,
    warehouse_id    UUID        NOT NULL,
    confirmed       BOOLEAN     NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE reception_line_projection (
    reception_line_id UUID PRIMARY KEY,
    reception_id      UUID          NOT NULL REFERENCES reception_projection (reception_id),
    material_id       UUID          NOT NULL,
    sku               VARCHAR(40),
    material_name     VARCHAR(150),
    dispatched        NUMERIC(14, 3),
    received          NUMERIC(14, 3),
    shrinkage_percent NUMERIC(7, 2),
    tolerance_percent NUMERIC(5, 2)
);

CREATE INDEX idx_reception_line_projection_material ON reception_line_projection (reception_id, material_id);

-- Kernel compartido: idempotencia HTTP (Idempotency-Key), Transactional Outbox y consumidor idempotente.
CREATE TABLE idempotency_keys (
    organization_id       UUID         NOT NULL,
    idempotency_key       UUID         NOT NULL,
    request_method        VARCHAR(10)  NOT NULL,
    request_path          VARCHAR(500) NOT NULL,
    response_status       INT          NOT NULL,
    response_content_type VARCHAR(200),
    response_body         BYTEA,
    created_at            TIMESTAMPTZ  NOT NULL,
    user_id               UUID,
    actor_role            VARCHAR(50),
    request_fingerprint   VARCHAR(64),
    response_location     VARCHAR(500),
    response_expired      BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (organization_id, idempotency_key)
);

CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    organization_id UUID         NOT NULL,
    actor_id        UUID,
    correlation_id  VARCHAR(64),
    payload         JSONB        NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_pending ON outbox_events (occurred_at, id) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
