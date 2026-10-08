package pe.buildshield.discrepancy.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pe.buildshield.discrepancy.application.DiscrepancyCommandService;
import pe.buildshield.discrepancy.application.DiscrepancyQueryService;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.domain.model.Note;
import pe.buildshield.discrepancy.shared.error.ErrorResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/discrepancies")
@Tag(name = "Discrepancias", description = "US36: discrepancias de la recepción con evidencia, respuesta y resolución")
@SecurityRequirement(name = "bearer")
class DiscrepancyController {

    private final DiscrepancyCommandService commands;
    private final DiscrepancyQueryService queries;

    DiscrepancyController(DiscrepancyCommandService commands, DiscrepancyQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping
    @PreAuthorize("hasRole('SITE_MANAGER')")
    @Operation(summary = "Registrar una discrepancia",
            description = "El encargado de obra registra otra diferencia de una recepción de su obra (por ejemplo, "
                    + "un daño dentro de la tolerancia). Las de las líneas fuera de tolerancia se abren solas al "
                    + "confirmar la recepción.")
    @ApiResponse(responseCode = "201", description = "Discrepancia abierta",
            content = @Content(schema = @Schema(implementation = DiscrepancyResource.class),
                    examples = @ExampleObject(value = DiscrepancyApiExamples.DISCREPANCY)))
    @ApiResponse(responseCode = "404", description = "Recepción o material inexistentes o de otra obra",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ResponseEntity<DiscrepancyResource> create(@Valid @RequestBody CreateRequest request) {
        Discrepancy created = commands.create(new DiscrepancyCommandService.CreateDiscrepancy(request.receptionId(),
                request.materialId(), request.type(), request.quantity(), request.observation()));
        return ResponseEntity.created(URI.create("/api/v1/discrepancies/" + created.id()))
                .body(DiscrepancyResource.of(created));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER', 'SITE_MANAGER')")
    @Operation(summary = "Listar discrepancias",
            description = "El administrador ve todas; el encargado de obra, las de sus obras; el de almacén, las de "
                    + "sus almacenes. De la más reciente a la más antigua.")
    @ApiResponse(responseCode = "200", description = "Discrepancias visibles",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = DiscrepancyResource.class)),
                    examples = @ExampleObject(value = DiscrepancyApiExamples.DISCREPANCIES)))
    List<DiscrepancyResource> list(
            @Parameter(description = "Estado", example = "OPEN") @RequestParam(required = false) DiscrepancyStatus status,
            @Parameter(description = "Obra de destino", example = DiscrepancyApiExamples.WORKSITE_ID)
            @RequestParam(required = false) UUID worksiteId,
            @Parameter(description = "Despacho", example = DiscrepancyApiExamples.DISPATCH_ID)
            @RequestParam(required = false) UUID dispatchId) {
        return queries.list(status, worksiteId, dispatchId).stream().map(DiscrepancyResource::of).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER', 'SITE_MANAGER')")
    @Operation(summary = "Consultar una discrepancia", description = "Con su respuesta, escalamiento, resolución y "
            + "evidencias.")
    @ApiResponse(responseCode = "200", description = "Discrepancia",
            content = @Content(schema = @Schema(implementation = DiscrepancyResource.class),
                    examples = @ExampleObject(value = DiscrepancyApiExamples.DISCREPANCY)))
    @ApiResponse(responseCode = "404", description = "No existe o no la puede ver",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    DiscrepancyResource get(@Parameter(description = "Id de la discrepancia",
            example = DiscrepancyApiExamples.DISCREPANCY_ID) @PathVariable UUID id) {
        return DiscrepancyResource.of(queries.get(id));
    }

    @PostMapping(value = "/{id}/evidences", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SITE_MANAGER')")
    @Operation(summary = "Subir una foto de evidencia",
            description = "Multipart con el campo file: imagen JPG, PNG o WEBP de hasta 5 MB. Solo mientras la "
                    + "discrepancia no esté cerrada.")
    @ApiResponse(responseCode = "201", description = "Evidencia guardada",
            content = @Content(schema = @Schema(implementation = EvidenceResource.class),
                    examples = @ExampleObject(value = DiscrepancyApiExamples.EVIDENCE)))
    @ApiResponse(responseCode = "400", description = "Archivo vacío, de más de 5 MB o que no es JPG, PNG ni WEBP",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La discrepancia está cerrada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ResponseEntity<EvidenceResource> addEvidence(@Parameter(description = "Id de la discrepancia",
            example = DiscrepancyApiExamples.DISCREPANCY_ID) @PathVariable UUID id,
            @Parameter(description = "Foto JPG, PNG o WEBP de hasta 5 MB") @RequestPart("file") MultipartFile file)
            throws IOException {
        Evidence evidence = commands.addEvidence(id, new DiscrepancyCommandService.EvidenceUpload(
                file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        return ResponseEntity.created(URI.create(evidence.url())).body(EvidenceResource.of(evidence));
    }

    @GetMapping("/{id}/evidences/{evidenceId}/content")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER', 'SITE_MANAGER')")
    @Operation(summary = "Descargar la foto de una evidencia",
            description = "Devuelve la imagen con su tipo. La web la pide con el token (no como enlace público).")
    @ApiResponse(responseCode = "200", description = "Imagen de la evidencia",
            content = @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "No existe o no la puede ver",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ResponseEntity<byte[]> evidenceContent(
            @Parameter(description = "Id de la discrepancia", example = DiscrepancyApiExamples.DISCREPANCY_ID)
            @PathVariable UUID id,
            @Parameter(description = "Id de la evidencia", example = DiscrepancyApiExamples.EVIDENCE_ID)
            @PathVariable UUID evidenceId) {
        var file = queries.evidence(id, evidenceId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.evidence().contentType()))
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "inline; filename=\"" + file.evidence().fileName().replace("\"", "")
                        + "\"")
                .body(file.content());
    }

    @PostMapping("/{id}/response")
    @PreAuthorize("hasAnyRole('WAREHOUSE_MANAGER', 'ADMINISTRATOR')")
    @Operation(summary = "Responder una discrepancia", description = "El almacén responde una discrepancia abierta.")
    @ApiResponse(responseCode = "200", description = "Discrepancia respondida",
            content = @Content(schema = @Schema(implementation = DiscrepancyResource.class),
                    examples = @ExampleObject(value = DiscrepancyApiExamples.DISCREPANCY)))
    DiscrepancyResource respond(@Parameter(description = "Id de la discrepancia",
            example = DiscrepancyApiExamples.DISCREPANCY_ID) @PathVariable UUID id,
            @Valid @RequestBody TextRequest request) {
        return DiscrepancyResource.of(commands.respond(id, request.text()));
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasRole('SITE_MANAGER')")
    @Operation(summary = "Aceptar la respuesta", description = "La obra acepta la respuesta y la discrepancia se "
            + "cierra. Solo respondidas.")
    @ApiResponse(responseCode = "200", description = "Discrepancia aceptada",
            content = @Content(schema = @Schema(implementation = DiscrepancyResource.class),
                    examples = @ExampleObject(value = DiscrepancyApiExamples.DISCREPANCY)))
    DiscrepancyResource accept(@Parameter(description = "Id de la discrepancia",
            example = DiscrepancyApiExamples.DISCREPANCY_ID) @PathVariable UUID id) {
        return DiscrepancyResource.of(commands.accept(id));
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize("hasRole('SITE_MANAGER')")
    @Operation(summary = "Escalar la discrepancia", description = "La obra no acepta la respuesta y la escala al "
            + "administrador con un motivo. Solo respondidas.")
    @ApiResponse(responseCode = "200", description = "Discrepancia escalada",
            content = @Content(schema = @Schema(implementation = DiscrepancyResource.class),
                    examples = @ExampleObject(value = DiscrepancyApiExamples.DISCREPANCY)))
    DiscrepancyResource escalate(@Parameter(description = "Id de la discrepancia",
            example = DiscrepancyApiExamples.DISCREPANCY_ID) @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request) {
        return DiscrepancyResource.of(commands.escalate(id, request.reason()));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Operation(summary = "Resolver la discrepancia", description = "El administrador registra la decisión final y "
            + "la discrepancia se cierra. Solo escaladas.")
    @ApiResponse(responseCode = "200", description = "Discrepancia resuelta",
            content = @Content(schema = @Schema(implementation = DiscrepancyResource.class),
                    examples = @ExampleObject(value = DiscrepancyApiExamples.DISCREPANCY)))
    DiscrepancyResource resolve(@Parameter(description = "Id de la discrepancia",
            example = DiscrepancyApiExamples.DISCREPANCY_ID) @PathVariable UUID id,
            @Valid @RequestBody DecisionRequest request) {
        return DiscrepancyResource.of(commands.resolve(id, request.decision()));
    }

    @Schema(description = "Discrepancia registrada a mano")
    record CreateRequest(@NotNull UUID receptionId, @NotNull UUID materialId,
            @Schema(example = "DAMAGE") @NotNull DiscrepancyType type,
            @Schema(example = "2") @NotNull BigDecimal quantity,
            @Schema(description = "De 3 a 500 caracteres", example = "Dos bolsas rotas en la descarga")
            String observation) {
    }

    @Schema(description = "Respuesta del almacén")
    record TextRequest(@Schema(description = "De 3 a 500 caracteres", example = "Se repone mañana") String text) {
    }

    @Schema(description = "Motivo del escalamiento")
    record ReasonRequest(@Schema(description = "De 3 a 500 caracteres", example = "La reposición no llegó")
            String reason) {
    }

    @Schema(description = "Decisión final del administrador")
    record DecisionRequest(@Schema(description = "De 3 a 500 caracteres",
            example = "Se descuenta al transportista") String decision) {
    }

    @Schema(description = "Paso registrado con su autor")
    record ResponseNote(String text, UUID by, Instant at) {
        static ResponseNote of(Note note) {
            return note == null ? null : new ResponseNote(note.text(), note.by(), note.at());
        }
    }

    @Schema(description = "Escalamiento")
    record EscalationNote(String reason, UUID by, Instant at) {
        static EscalationNote of(Note note) {
            return note == null ? null : new EscalationNote(note.text(), note.by(), note.at());
        }
    }

    @Schema(description = "Resolución")
    record ResolutionNote(String decision, UUID by, Instant at) {
        static ResolutionNote of(Note note) {
            return note == null ? null : new ResolutionNote(note.text(), note.by(), note.at());
        }
    }

    @Schema(description = "Foto de evidencia")
    record EvidenceResource(UUID id, String url, String fileName, UUID uploadedBy, Instant uploadedAt) {
        static EvidenceResource of(Evidence evidence) {
            return new EvidenceResource(evidence.id(), evidence.url(), evidence.fileName(), evidence.uploadedBy(),
                    evidence.uploadedAt());
        }
    }

    @Schema(description = "Discrepancia")
    record DiscrepancyResource(UUID id, UUID organizationId, UUID receptionId, UUID dispatchId, String manifestCode,
            UUID worksiteId, UUID warehouseId, UUID materialId, String sku, String materialName, DiscrepancyType type,
            String typeLabel, BigDecimal quantity, BigDecimal shrinkagePercent, BigDecimal tolerancePercent,
            String observation, DiscrepancyStatus status, String statusLabel, UUID openedBy, Instant openedAt,
            ResponseNote response, EscalationNote escalation, ResolutionNote resolution,
            List<EvidenceResource> evidences) {

        static DiscrepancyResource of(Discrepancy discrepancy) {
            var reception = discrepancy.reception();
            var material = discrepancy.material();
            return new DiscrepancyResource(discrepancy.id(), pe.buildshield.discrepancy.shared.tenant.TenantContext
                    .require().organizationId(), reception.receptionId(), reception.dispatchId(),
                    reception.manifestCode(), reception.worksiteId(), reception.warehouseId(), material.materialId(),
                    material.sku(), material.name(), discrepancy.type(), discrepancy.type().label(),
                    discrepancy.quantity(), discrepancy.shrinkagePercent(), discrepancy.tolerancePercent(),
                    discrepancy.observation(), discrepancy.status(), discrepancy.status().label(),
                    discrepancy.openedBy(), discrepancy.openedAt(), ResponseNote.of(discrepancy.response()),
                    EscalationNote.of(discrepancy.escalation()), ResolutionNote.of(discrepancy.resolution()),
                    discrepancy.evidences().stream().map(EvidenceResource::of).toList());
        }
    }
}
