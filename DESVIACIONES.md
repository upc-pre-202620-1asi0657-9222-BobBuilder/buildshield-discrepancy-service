# Desviaciones frente a la arquitectura v4

Servicio `buildshield-discrepancy-service`. Fuente: ER v4 pág. 18 y C4 pág. 23 de `buildshield-presentacion-v4.pdf`,
con las reglas del Anexo A.10 de la tarea. Todo lo que no está aquí coincide con el ER y el C4.

## Tablas extra

| Tabla | Motivo |
|---|---|
| `assignment_projection` | Copia de `StaffAssigned` y `StaffUnassigned` de organization: qué obras ve cada encargado de obra y qué almacenes ve cada encargado de almacén. Sin ella, filtrar la lista y el detalle por rol (QAS04) exigiría llamar a organization por REST. Ya estaba prevista en la tarea como desviación permitida. |

Se eliminaron `material_projection`, `reception_projection` y `reception_line_projection`, porque `ReceptionConfirmed` trae el material, la unidad y las cantidades de cada línea.

## Columnas extra en tablas del ER

| Tabla | Columna | Motivo |
|---|---|---|
| `discrepancies` | `reception_id`, `dispatch_id`, `worksite_id`, `warehouse_id` (uuid, LR a ordering y organization) | Filtro por obra (encargado de obra) y por almacén (encargado de almacén), filtros `worksiteId` y `dispatchId` de la lista, y el payload de los eventos (`dispatchId`, `receptionId`, `worksiteId`, `warehouseId`). |
| `discrepancies` | `material_id` (uuid, LR a organization) | El payload de los eventos lleva `materialId`. |
| `discrepancies` | `manifest_code`, `sku`, `material_name`, `unit` | Copiados de `ReceptionConfirmed`. Los usan la API (la web muestra el material y el despacho) y los eventos: notification arma el mensaje con `materialName` o `sku` y `manifestCode`, y audit concilia con `sku`, `materialName` y `manifestCode`. La tarea pide `unit` en el payload. |
| `discrepancies` | `version` | Bloqueo optimista entre respuestas simultáneas desde el celular (kernel compartido). |
| `discrepancies` | `created_at`, `created_by`, `updated_at`, `updated_by` | Auditoría del kernel compartido. `created_by` y `created_at` son quién y cuándo se abrió la discrepancia (`openedBy` y `openedAt` en la API y en los eventos). |
| `discrepancy_evidences`, `discrepancy_responses` | `organization_id` | Regla común A.5: toda tabla de negocio lleva `organization_id` con índice que empieza por ella. Es el filtro multiempresa de Hibernate. |
| `outbox_events` | `actor_id` | Va en el sobre del evento (`actorId`, A.6). Lo leen audit y notification. |
| `outbox_events` | `correlation_id` | Cabecera Kafka de correlación para seguir una operación entre servicios. |

## Componentes fuera del C4

| Clase | Motivo |
|---|---|
| `AssignmentEventConsumer` | Consume `StaffAssigned` y `StaffUnassigned` de `buildshield.organization` y mantiene `assignment_projection` (ver arriba). El C4 solo dibuja `ReceptionEventConsumer`, que consume de ordering. Juntar los dos tópicos en un solo consumidor mezclaría dos contextos. |

Las demás clases nuevas son de apoyo, no componentes de arquitectura (A.4):
- `DiscrepancyVisibility`: filtro por rol que usan los servicios.
- `EvidenceUrlSigner`: URL firmada con HMAC.
- `StaffAssignments`: puerto de dominio de la copia.
- Entidades JPA, DTO y `Payloads`.

## Valores que fija este servicio

- `discrepancy_responses.decision`: `RESPOND`, `ACCEPT`, `ESCALATE` o `RESOLVE`. El ER no enumera los valores. Una aceptación queda con `comment = 'Respuesta aceptada'`.
- `idempotency_keys.status`: `COMPLETED` o `EXPIRED`. `response_body` guarda el código HTTP, el tipo, la ubicación, el cuerpo y el dueño (usuario y rol) de la operación. Al vencer la respuesta se borra el cuerpo y se conserva el dueño.
- Tipo de discrepancia: `SURPLUS` si llegó más de lo despachado. Si llegó menos, `SHRINKAGE` cuando el material admite tolerancia (`tolerancePercent > 0`) y `SHORTAGE` cuando no admite ninguna. El ER no fija la regla; se apoya en el `tolerancePercent` de cada línea.

## Funcionalidad retirada

- `POST /api/v1/discrepancies` (registro manual de una discrepancia por el encargado de obra) y la columna `origin`. Los endpoints de la v4 son listar, ver detalle, subir evidencia, responder, aceptar, escalar y resolver, y el ER exige `reception_line_id`. Sin `reception_projection`, el servicio tampoco podría validar la recepción. La migración `V2` borra las discrepancias manuales que hubiera en la base, con sus evidencias.
