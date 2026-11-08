# Desviaciones frente a la arquitectura v4

Servicio `buildshield-discrepancy-service`. Fuente: ER v4 pág. 18 y C4 pág. 23 de `buildshield-presentacion-v4.pdf`,
con las reglas del Anexo A.10 de la tarea. Todo lo que no está aquí coincide con el ER y el C4.

## Tablas extra

| Tabla | Motivo |
|---|---|
| `assignment_projection` | Copia de `StaffAssigned` y `StaffUnassigned` de organization: qué obras ve cada encargado de obra y qué almacenes ve cada encargado de almacén. Sin ella, filtrar la lista y el detalle por rol (QAS04) exigiría llamar a organization por REST. Ya estaba prevista en la tarea como desviación permitida. |
| `reception_projection` | ERD ampliado por el equipo (migración V4). Copia de `ReceptionOpened` y `ReceptionConfirmed`: `reception_id`, `organization_id`, `dispatch_id`, `manifest_code`, `worksite_id`, `warehouse_id`. `POST /discrepancies` valida con ella que la recepción exista y sea de una obra del encargado, sin llamar a ordering por REST. |
| `reception_line_projection` | ERD ampliado por el equipo (migración V4). Líneas que trae `ReceptionConfirmed`: `reception_line_id`, `reception_id`, `material_id`, `sku`, `material_name`, `shrinkage_percent`, `tolerance_percent`. Valida que el material sea de la recepción y completa el registro manual. Los nombres vienen de la V1; no se recuperan `confirmed`, `updated_at`, `dispatched` ni `received` porque nada los lee. |

Se eliminó `material_projection`, porque `ReceptionConfirmed` trae el material, la unidad y las cantidades de cada línea.

Límites de la copia de recepciones:

- Las recepciones anteriores a la migración V4 no están en `reception_projection`: `POST /discrepancies` responde 404 `RECEPTION_NOT_FOUND` hasta que llegue un evento nuevo de esa recepción (`ReceptionConfirmed` la crea completa).
- `ReceptionOpened` no trae líneas: antes de confirmar la recepción, el registro manual responde 404 `MATERIAL_NOT_FOUND`. La web solo ofrece el formulario después de confirmar.
- La copia no guarda la unidad: la discrepancia manual queda con `unit` nula, igual que en su evento.

## Columnas extra en tablas del ER

| Tabla | Columna | Motivo |
|---|---|---|
| `discrepancies` | `reception_id`, `dispatch_id`, `worksite_id`, `warehouse_id` (uuid, LR a ordering y organization) | Filtro por obra (encargado de obra) y por almacén (encargado de almacén), filtros `worksiteId` y `dispatchId` de la lista, y el payload de los eventos (`dispatchId`, `receptionId`, `worksiteId`, `warehouseId`). |
| `discrepancies` | `material_id` (uuid, LR a organization) | El payload de los eventos lleva `materialId`. |
| `discrepancies` | `manifest_code`, `sku`, `material_name`, `unit` | Copiados de `ReceptionConfirmed`. Los usan la API (la web muestra el material y el despacho) y los eventos: notification arma el mensaje con `materialName` o `sku` y `manifestCode`, y audit concilia con `sku`, `materialName` y `manifestCode`. La tarea pide `unit` en el payload. |
| `discrepancies` | `version` | Bloqueo optimista entre respuestas simultáneas desde el celular (kernel compartido). |
| `discrepancies` | `created_at`, `created_by`, `updated_at`, `updated_by` | Auditoría del kernel compartido. `created_by` y `created_at` son quién y cuándo se abrió la discrepancia (`openedBy` y `openedAt` en la API y en los eventos). |
| `discrepancy_evidences`, `discrepancy_responses` | `organization_id` | Regla común A.5: toda tabla de negocio lleva `organization_id` con índice que empieza por ella. Es el filtro multiempresa de Hibernate. |
| `discrepancies` | `observation` (varchar 500, nula) | ERD ampliado (aceptada). Texto libre del registro manual; la API la devuelve como `observation`. Nula en las automáticas y en las filas anteriores a V4. |
| `discrepancies` | `origin` (varchar 10) | ERD ampliado (aceptada). `AUTOMATIC` o `MANUAL`; las filas anteriores a V4 quedan `AUTOMATIC`. |
| `discrepancies` | `shrinkage_pct` (numeric 7,2, nula), `tolerance_pct` (numeric 5,2, nula) | ERD ampliado (aceptada). Salen de `ReceptionConfirmed`; la API los devuelve como `shrinkagePercent` y `tolerancePercent`. Nulas en las filas anteriores a V4, porque V2 borró esos datos. |
| `outbox_events` | `correlation_id` | Cabecera Kafka de correlación para seguir una operación entre servicios. |

## Outbox

- `outbox_events` ya no tiene `actor_id` (migración V3). El sobre completo del evento, con `aggregateType` y `actorId` (A.6), se guarda en `payload` y el publicador lo envía tal cual, como en iam. Audit y notification siguen leyendo `actorId` del sobre.

## Componentes fuera del C4

| Clase | Motivo |
|---|---|
| `ReceptionProjections`, `JdbcReceptionProjections` | Puerto y adaptador de la copia de recepciones (ver arriba). Los alimenta `ReceptionEventConsumer`, de forma idempotente por `processed_events`. |
| `AssignmentEventConsumer` | Consume `StaffAssigned` y `StaffUnassigned` de `buildshield.organization` y mantiene `assignment_projection` (ver arriba). El C4 solo dibuja `ReceptionEventConsumer`, que consume de ordering. Juntar los dos tópicos en un solo consumidor mezclaría dos contextos. |

Las demás clases nuevas son de apoyo, no componentes de arquitectura (A.4):
- `DiscrepancyVisibility`: filtro por rol que usan los servicios.
- `S3EvidenceStorageAdapter`: el `EvidenceStorageAdapter` del C4 en la nube; la carpeta local queda para desarrollo y pruebas.
- `EvidenceUrlSigner`: URL firmada con HMAC.
- `StaffAssignments`: puerto de dominio de la copia.
- Entidades JPA, DTO y `Payloads`.

## Valores que fija este servicio

- `discrepancy_responses.decision`: `RESPOND`, `ACCEPT`, `ESCALATE` o `RESOLVE`. El ER no enumera los valores. Una aceptación queda con `comment = 'Respuesta aceptada'`.
- `idempotency_keys.status`: `COMPLETED` o `EXPIRED`. `response_body` guarda el código HTTP, el tipo, la ubicación, el cuerpo y el dueño (usuario y rol) de la operación. Al vencer la respuesta se borra el cuerpo y se conserva el dueño.
- Tipo de discrepancia automática: `SURPLUS` si llegó más de lo despachado. Si llegó menos, `SHRINKAGE` cuando el material admite tolerancia (`tolerancePercent > 0`) y `SHORTAGE` cuando no admite ninguna. El ER no fija la regla; se apoya en el `tolerancePercent` de cada línea.
- Tipo de discrepancia manual: `SHORTAGE`, `DAMAGE`, `EXCESS` o `WRONG_MATERIAL`, los del formulario de la web. El check `ck_discrepancies_type` y el enum aceptan la unión de ambos grupos; ningún valor guardado se renombra. `SURPLUS` y `SHRINKAGE` a mano responden 400 `INVALID_DISCREPANCY_TYPE`.
- `reception_line_id` es nula solo en las manuales (`ck_discrepancies_reception_line`) y sigue siendo única cuando existe (índice parcial `uk_discrepancies_reception_line`). `uk_discrepancies_open` deja a lo más una discrepancia sin cerrar por recepción, material y tipo.
- `reason` de una discrepancia manual: la observación recortada a 60 caracteres.
