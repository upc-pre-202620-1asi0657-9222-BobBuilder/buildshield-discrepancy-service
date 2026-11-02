-- Alineación con el ER corregido de discrepancy_db (wiki/informe-avance2/er-corregido.md).
-- outbox_events sin actor_id (no está en el ER): el sobre completo del evento (con aggregateType y actorId) va en
-- payload, como en iam; el publicador lo envía tal cual. Los eventos ya escritos se convierten al mismo formato.
UPDATE outbox_events SET payload = jsonb_build_object(
        'eventId', id, 'eventType', event_type,
        'occurredAt', to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'organizationId', organization_id,
        'aggregateType', upper((regexp_match(event_type, '^[A-Z][a-z0-9]*'))[1]),
        'aggregateId', aggregate_id, 'actorId', actor_id, 'version', 1, 'payload', payload)
WHERE NOT (payload ? 'eventId');
ALTER TABLE outbox_events DROP COLUMN actor_id;
