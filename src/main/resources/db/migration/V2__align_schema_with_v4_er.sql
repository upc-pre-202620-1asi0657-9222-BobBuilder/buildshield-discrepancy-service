-- Alinea discrepancy_db con el ER de la arquitectura v4 (pág. 18) y las tablas técnicas comunes (Anexo A.5).
-- Las columnas y tablas que siguen fuera del ER están justificadas en DESVIACIONES.md.

-- ---------- discrepancy_responses: respuesta, aceptación, escalamiento y resolución ----------
CREATE TABLE discrepancy_responses (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    discrepancy_id  UUID         NOT NULL REFERENCES discrepancies (id),
    author_id       UUID         NOT NULL,
    decision        VARCHAR(15)  NOT NULL,
    comment         VARCHAR(500) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_discrepancy_responses_decision CHECK (decision IN ('RESPOND', 'ACCEPT', 'ESCALATE', 'RESOLVE'))
);

CREATE INDEX idx_discrepancy_responses_discrepancy ON discrepancy_responses (organization_id, discrepancy_id,
    created_at);

-- Las notas que vivían en columnas de la discrepancia pasan a filas de discrepancy_responses.
INSERT INTO discrepancy_responses (id, organization_id, discrepancy_id, author_id, decision, comment, created_at)
SELECT gen_random_uuid(), organization_id, id, responded_by, 'RESPOND', response_text, responded_at
FROM discrepancies WHERE response_text IS NOT NULL;
INSERT INTO discrepancy_responses (id, organization_id, discrepancy_id, author_id, decision, comment, created_at)
SELECT gen_random_uuid(), organization_id, id, accepted_by, 'ACCEPT', 'Respuesta aceptada', accepted_at
FROM discrepancies WHERE accepted_by IS NOT NULL;
INSERT INTO discrepancy_responses (id, organization_id, discrepancy_id, author_id, decision, comment, created_at)
SELECT gen_random_uuid(), organization_id, id, escalated_by, 'ESCALATE', escalation_reason, escalated_at
FROM discrepancies WHERE escalation_reason IS NOT NULL;
INSERT INTO discrepancy_responses (id, organization_id, discrepancy_id, author_id, decision, comment, created_at)
SELECT gen_random_uuid(), organization_id, id, resolved_by, 'RESOLVE', resolution, resolved_at
FROM discrepancies WHERE resolution IS NOT NULL;

-- ---------- discrepancies ----------
-- La v4 ya no registra discrepancias a mano: solo las abre ReceptionConfirmed, siempre con su línea.
DELETE FROM discrepancy_responses
WHERE discrepancy_id IN (SELECT id FROM discrepancies WHERE reception_line_id IS NULL);
DELETE FROM discrepancy_evidences
WHERE discrepancy_id IN (SELECT id FROM discrepancies WHERE reception_line_id IS NULL);
DELETE FROM discrepancies WHERE reception_line_id IS NULL;

ALTER TABLE discrepancies
    DROP CONSTRAINT ck_discrepancies_type,
    DROP CONSTRAINT ck_discrepancies_origin,
    DROP CONSTRAINT ck_discrepancies_response,
    DROP CONSTRAINT ck_discrepancies_escalation,
    DROP CONSTRAINT ck_discrepancies_resolution;

UPDATE discrepancies SET type = 'SURPLUS' WHERE type = 'EXCESS';
-- Merma (SHRINKAGE) si el material admite tolerancia; faltante (SHORTAGE) si no admite ninguna pérdida.
UPDATE discrepancies SET type = 'SHRINKAGE' WHERE type = 'SHORTAGE' AND tolerance_pct > 0;

ALTER TABLE discrepancies
    ADD COLUMN reason VARCHAR(60),
    ADD COLUMN unit   VARCHAR(10);

UPDATE discrepancies SET reason = CASE type
    WHEN 'SURPLUS' THEN 'Llegó más de lo despachado'
    WHEN 'SHRINKAGE' THEN 'Merma de ' || COALESCE(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM shrinkage_pct::text)), '?')
        || ' % sobre ' || TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM tolerance_pct::text)) || ' % tolerado'
    ELSE 'Llegó menos de lo despachado' END;

ALTER TABLE discrepancies
    ALTER COLUMN reason SET NOT NULL,
    ALTER COLUMN reception_line_id SET NOT NULL,
    DROP COLUMN origin,
    DROP COLUMN observation,
    DROP COLUMN shrinkage_pct,
    DROP COLUMN tolerance_pct,
    DROP COLUMN opened_by,
    DROP COLUMN opened_at,
    DROP COLUMN response_text,
    DROP COLUMN responded_by,
    DROP COLUMN responded_at,
    DROP COLUMN escalation_reason,
    DROP COLUMN escalated_by,
    DROP COLUMN resolution,
    DROP COLUMN resolved_by,
    DROP COLUMN accepted_by,
    DROP COLUMN accepted_at,
    ADD CONSTRAINT ck_discrepancies_type CHECK (type IN ('SHORTAGE', 'SURPLUS', 'SHRINKAGE')),
    ADD CONSTRAINT ck_discrepancies_escalated CHECK (status NOT IN ('ESCALATED', 'RESOLVED')
        OR escalated_at IS NOT NULL),
    ADD CONSTRAINT ck_discrepancies_resolved CHECK (status <> 'RESOLVED' OR resolved_at IS NOT NULL);

-- QAS10: una discrepancia por línea de recepción, aunque ReceptionConfirmed llegue más de una vez.
DROP INDEX uk_discrepancies_reception_line;
DROP INDEX uk_discrepancies_open;
ALTER TABLE discrepancies ADD CONSTRAINT uk_discrepancies_reception_line UNIQUE (reception_line_id);

-- ---------- discrepancy_evidences ----------
-- photo_url guarda la clave relativa del archivo (organización/discrepancia/evidencia.ext); la URL firmada se arma
-- al responder.
ALTER TABLE discrepancy_evidences ADD COLUMN photo_url VARCHAR(255);
UPDATE discrepancy_evidences SET photo_url = storage_key;
ALTER TABLE discrepancy_evidences
    ALTER COLUMN photo_url SET NOT NULL,
    DROP CONSTRAINT ck_discrepancy_evidences_size,
    DROP CONSTRAINT ck_discrepancy_evidences_type,
    DROP COLUMN storage_key,
    DROP COLUMN url,
    DROP COLUMN file_name,
    DROP COLUMN content_type,
    DROP COLUMN size_bytes,
    DROP COLUMN uploaded_by;

-- ---------- Copias locales ----------
-- ReceptionConfirmed trae todo lo de la recepción y del material: estas copias sobran. assignment_projection se
-- queda (qué obras y almacenes ve cada encargado).
DROP TABLE reception_line_projection;
DROP TABLE reception_projection;
DROP TABLE material_projection;

-- ---------- Tablas técnicas (Anexo A.5) ----------
-- Las claves de idempotencia solo valen 24 horas: se recrea la tabla con el esquema común.
DROP TABLE idempotency_keys;
CREATE TABLE idempotency_keys (
    key             UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    endpoint        VARCHAR(80) NOT NULL,
    request_hash    CHAR(64)    NOT NULL,
    status          VARCHAR(12) NOT NULL,
    response_body   JSONB       NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (key, organization_id),
    CONSTRAINT ck_idempotency_keys_status CHECK (status IN ('COMPLETED', 'EXPIRED'))
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at) WHERE status = 'COMPLETED';

-- El tipo de agregado viaja solo en el sobre y se deduce del event_type.
ALTER TABLE outbox_events
    DROP COLUMN aggregate_type,
    ALTER COLUMN aggregate_id TYPE UUID USING aggregate_id::uuid,
    ALTER COLUMN event_type TYPE VARCHAR(60);

-- Un mismo evento lo pueden procesar dos consumidores del servicio. Lo ya procesado se atribuye al consumidor de
-- recepciones; si un evento de organization se repitiera, el consumidor de asignaciones lo aplica otra vez sin
-- efecto (las copias se actualizan con upsert).
ALTER TABLE processed_events ADD COLUMN consumer VARCHAR(60) NOT NULL DEFAULT 'ReceptionEventConsumer';
ALTER TABLE processed_events ALTER COLUMN consumer DROP DEFAULT;
ALTER TABLE processed_events DROP CONSTRAINT processed_events_pkey;
ALTER TABLE processed_events ADD PRIMARY KEY (event_id, consumer);
