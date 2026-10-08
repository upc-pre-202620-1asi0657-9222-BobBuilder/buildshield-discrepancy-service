# buildshield-discrepancy-service

Gestión de Discrepancias de BuildShield (BobBuilder): diferencias de la recepción con evidencia fotográfica,
respuesta del almacén, aceptación o escalamiento y decisión final (US36). Contrato de la arquitectura:
`wiki/informe-tf/plan-microservicios.md` y bloque «Discrepancias» de `wiki/informe-avance4/contrato-api-sprint3.md`.

| Dato | Valor |
|---|---|
| Puerto | 8085 |
| Base | `discrepancy_db` (PostgreSQL 16+) |
| Paquete | `pe.buildshield.discrepancy` (kernel compartido copiado de iam en `pe.buildshield.discrepancy.shared`) |
| Publica | `buildshield.discrepancy`: `DiscrepancyOpened`, `DiscrepancyResponded`, `DiscrepancyAccepted`, `DiscrepancyEscalated`, `DiscrepancyResolved`. Payload: `discrepancyId, receptionId, receptionLineId, dispatchId, manifestCode, worksiteId, warehouseId, materialId, sku, materialName, type, quantity, status, origin, openedBy, text` (`text` = observación, respuesta, motivo o decisión según el evento) |
| Consume | `buildshield.organization` (`MaterialRegistered/Updated` → `material_projection`, `StaffAssigned/Unassigned` → `assignment_projection`) y `buildshield.ordering` (`ReceptionOpened`, `ReceptionPartiallyReceived` → `reception_projection`; `ReceptionConfirmed` → líneas de la recepción y apertura automática) |

## Reglas

- Estados `OPEN → RESPONDED → ACCEPTED` (cierra) o `RESPONDED → ESCALATED → RESOLVED` (cierra). Transición inválida:
  409 `INVALID_DISCREPANCY_TRANSITION`. Rol incorrecto: 403.
- Apertura automática: al consumir `ReceptionConfirmed` (no en un parcial), una discrepancia por cada línea con
  `withinTolerance = false`: `SHORTAGE` si llegó menos de lo despachado, `EXCESS` si llegó más, por la diferencia
  absoluta. Única por `reception_line_id` (QAS10): la misma confirmación repetida, o reenviada con otro `eventId`, no
  duplica nada.
- A lo más una discrepancia sin cerrar por recepción, material y tipo (409 `DISCREPANCY_ALREADY_OPEN`).
- Visibilidad como en el Core: el administrador todo; el encargado de obra, sus obras asignadas; el encargado de
  almacén, sus almacenes asignados. Lo que no ve es 404.
- Evidencias: imagen JPG, PNG o WEBP de hasta 5 MB, validada también por su firma de bytes. `EvidenceStorageAdapter`
  la guarda en `EVIDENCE_LOCAL_DIR` (por defecto `data/evidence`); en la nube se reemplaza por Amazon S3 con la misma
  clave `organización/discrepancia/evidencia.ext`.
- `Idempotency-Key` obligatoria en `POST /discrepancies` y en `response`, `accept`, `escalate` y `resolve` (la web la
  agrega con su interceptor). La subida de evidencias no la usa.

## Endpoints

- `POST /api/v1/discrepancies` (SITE_MANAGER) `{"receptionId","materialId","type","quantity","observation"}` → 201
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
(apertura automática, registro con foto, respuesta y aceptación, escalamiento y resolución, transiciones inválidas,
roles, visibilidad e idempotencia). JaCoCo exige 80 % de líneas en `domain` y `application`.
