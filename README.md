# buildshield-discrepancy-service

Gestión de Discrepancias de BuildShield (BobBuilder): diferencias de la recepción con evidencia fotográfica,
respuesta del almacén, aceptación o escalamiento y decisión final (US36, RF32 a RF35). Fuente fija: arquitectura v4
(`buildshield-presentacion-v4.pdf`, ER pág. 18 y C4 pág. 23); bloque «Discrepancias» de
`wiki/informe-avance4/contrato-api-sprint3.md`. Lo que se aparta del ER o del C4 está en [DESVIACIONES.md](DESVIACIONES.md).

| Dato | Valor |
|---|---|
| Puerto | 8085 |
| Base | `discrepancy_db` (PostgreSQL 16+) |
| Paquete | `pe.buildshield.discrepancy` (kernel compartido copiado de iam en `pe.buildshield.discrepancy.shared`) |
| Tablas | `discrepancies`, `discrepancy_evidences`, `discrepancy_responses`, `assignment_projection`, `idempotency_keys`, `outbox_events`, `processed_events` |
| Componentes (C4) | `DiscrepanciesController`, `DiscrepanciesCommandService`, `DiscrepanciesQueryService`, dominio (`Discrepancy`, `Evidence`, `Response`, `Resolution`), `DiscrepanciesOutboxPublisher`, `DiscrepanciesRepository`, `EvidenceStorageAdapter`, `ReceptionEventConsumer` |
| Publica | `buildshield.discrepancy`: `DiscrepancyOpened`, `DiscrepancyResponded`, `DiscrepancyAccepted`, `DiscrepancyEscalated`, `DiscrepancyResolved`. Payload: `discrepancyId, receptionId, receptionLineId, dispatchId, manifestCode, worksiteId, warehouseId, materialId, sku, materialName, unit, type, quantity, status, openedBy, text` (`text` = motivo, respuesta, motivo del escalamiento o decisión según el evento) |
| Consume | `buildshield.ordering`: `ReceptionConfirmed` (`ReceptionEventConsumer`, apertura automática). `buildshield.organization`: `StaffAssigned/Unassigned` → `assignment_projection` (`AssignmentEventConsumer`, desviación documentada) |

## Reglas

- Estados `OPEN → RESPONDED → ACCEPTED` (cierra) o `RESPONDED → ESCALATED → RESOLVED` (cierra). Transición inválida:
  409 `INVALID_DISCREPANCY_TRANSITION`. Rol incorrecto: 403.
- Apertura automática: al consumir `ReceptionConfirmed` (no en un parcial), una discrepancia por cada línea con
  `withinTolerance = false`, por la diferencia absoluta: `SURPLUS` si llegó más de lo despachado; si llegó menos,
  `SHRINKAGE` cuando el material admite tolerancia (> 0 %) y `SHORTAGE` cuando no. `reason` (60 caracteres) resume la
  diferencia. Única por `reception_line_id` (QAS10): la misma confirmación repetida, o reenviada con otro `eventId`,
  no duplica nada. El evento trae material, unidad y cantidades: no se consulta a ordering ni a organization.
- Cada paso (responder, aceptar, escalar, resolver) es una fila de `discrepancy_responses` (`decision`, `comment`,
  `author_id`); `escalated_at` y `resolved_at` quedan en la discrepancia.
- Visibilidad como en el Core: el administrador todo; el encargado de obra, sus obras asignadas; el encargado de
  almacén, sus almacenes asignados. Lo que no ve es 404.
- Evidencias: imagen JPG, PNG o WEBP de hasta 5 MB, validada también por su firma de bytes. `EvidenceStorageAdapter`
  la guarda en `EVIDENCE_LOCAL_DIR` (por defecto `data/evidence`); en la nube se reemplaza por Amazon S3 con la misma
  clave `organización/discrepancia/evidencia.ext`, que es el `photo_url`. La foto se sirve por URL relativa firmada con
  HMAC, válida 30 minutos.
- `Idempotency-Key` obligatoria en `response`, `accept`, `escalate` y `resolve` (la web la agrega con su
  interceptor); se guarda en `idempotency_keys` con llave `(key, organization_id)`. La subida de evidencias no la usa.

## Endpoints

- `GET /api/v1/discrepancies?status=&worksiteId=&dispatchId=` y `GET /api/v1/discrepancies/{id}` (los tres roles,
  filtrado por rol)
- `POST /api/v1/discrepancies/{id}/evidences` (SITE_MANAGER, multipart `file`) → 201 con la evidencia
- `GET /api/v1/discrepancies/{id}/evidences/{evidenceId}/content`: la imagen (la `url` de cada evidencia apunta
  aquí; la web la pide con el token)
- `POST /api/v1/discrepancies/{id}/response` (WAREHOUSE_MANAGER o ADMINISTRATOR) `{"text"}`
- `POST /api/v1/discrepancies/{id}/accept` (SITE_MANAGER)
- `POST /api/v1/discrepancies/{id}/escalate` (SITE_MANAGER) `{"reason"}`
- `POST /api/v1/discrepancies/{id}/resolve` (ADMINISTRATOR) `{"decision"}`
- OpenAPI en `/api/v1/api-docs` y `/swagger-ui.html` (perfiles local y test).

## Variables de entorno

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` (por defecto `localhost:9092`),
`BUILDSHIELD_JWT_PUBLIC_KEY` y `EVIDENCE_LOCAL_DIR`. Perfiles `local`, `test` y `prod`.

## Ejecutar en local (clúster PostgreSQL 127.0.0.1:5433 con autenticación trust)

```bash
mvn -B package -DskipTests
BUILDSHIELD_JWT_PUBLIC_KEY="$(cat ~/.buildshield/jwt-public.pem)" \
java -jar target/buildshield-discrepancy-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

## Pruebas

```bash
mvn -B verify -Dbuildshield.test.external-database=true
```

Las `*IT` usan la base temporal `discrepancy_it` en `127.0.0.1:5433` con el usuario `buildshield_core` y Kafka
embebido. `DiscrepancyMessagingIT` prueba QAS10 con entregas repetidas de `ReceptionConfirmed`. Cucumber cubre US36
(apertura automática y tipos, fotos, respuesta y aceptación, escalamiento y resolución, transiciones inválidas,
roles, visibilidad e idempotencia). ArchUnit verifica que el dominio no dependa de Spring ni JPA y que los
controladores no toquen repositorios. JaCoCo exige 80 % de líneas en `domain` y `application`.
