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
import pe.buildshield.discrepancy.application.DiscrepanciesCommandService;
import pe.buildshield.discrepancy.application.DiscrepanciesQueryService;
import pe.buildshield.discrepancy.application.EvidenceUrlSigner;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.domain.model.Resolution;
import pe.buildshield.discrepancy.domain.model.Response;
import pe.buildshield.discrepancy.shared.error.ErrorResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@RestController
@RequestMapping("/api/v1/discrepancies")
@Tag(name = "Discrepancias", description = "US36: discrepancias de la recepción con evidencia, respuesta y resolución")
@SecurityRequirement(name = "bearer")
class DiscrepanciesController {

    private final DiscrepanciesCommandService commands;
    private final DiscrepanciesQueryService queries;
    private final EvidenceUrlSigner signer;

    DiscrepanciesController(DiscrepanciesCommandService commands, DiscrepanciesQueryService queries,
            EvidenceUrlSigner signer) {
        this.commands = commands;
        this.queries = queries;
        this.signer = signer;
    }

    private DiscrepancyResource resource(Discrepancy discrepancy) {
        UUID organizationId = TenantContext.require().organizationId();
        return DiscrepancyResource.of(organizationId, discrepancy,
                evidence -> signer.signedUrl(organizationId, discrepancy.id(), evidence));
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
        return queries.list(status, worksiteId, dispatchId).stream().map(this::resource).toList();
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
        return resource(queries.get(id));
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
        Evidence evidence = commands.addEvidence(id, new DiscrepanciesCommandService.EvidenceUpload(
                file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        String url = signer.signedUrl(TenantContext.require().organizationId(), id, evidence);
        return ResponseEntity.created(URI.create(EvidenceUrlSigner.contentPath(id, evidence.id())))
                .body(EvidenceResource.of(evidence, url));
    }

    @GetMapping("/{id}/evidences/{evidenceId}/content")
    @Operation(summary = "Descargar la foto de una evidencia",
            description = "Devuelve la imagen con su tipo. Es la url firmada de cada evidencia: se usa tal cual en "
                    + "<img src> (sin cabecera Authorization) y vence a los 30 minutos.")
    @ApiResponse(responseCode = "200", description = "Imagen de la evidencia",
            content = @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "No existe, el token no corresponde o ya venció",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ResponseEntity<byte[]> evidenceContent(
            @Parameter(description = "Id de la discrepancia", example = DiscrepancyApiExamples.DISCREPANCY_ID)
            @PathVariable UUID id,
            @Parameter(description = "Id de la evidencia", example = DiscrepancyApiExamples.EVIDENCE_ID)
            @PathVariable UUID evidenceId,
            @Parameter(description = "Firma de acceso que trae la url de la evidencia",
                    example = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b.1793722200.q1w2e3r4t5y6u7i8o9p0")
            @RequestParam(required = false) String token) {
        var file = queries.evidenceWithToken(id, evidenceId, token);
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
        return resource(commands.respond(id, request.text()));
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
        return resource(commands.accept(id));
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
        return resource(commands.escalate(id, request.reason()));
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
        return resource(commands.resolve(id, request.decision()));
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

    @Schema(description = "Respuesta del almacén con su autor")
    record ResponseNote(String text, UUID by, Instant at) {
        static ResponseNote of(Response step) {
            return new ResponseNote(step.comment(), step.authorId(), step.createdAt());
        }
    }

    @Schema(description = "Escalamiento")
    record EscalationNote(String reason, UUID by, Instant at) {
        static EscalationNote of(Response step) {
            return new EscalationNote(step.comment(), step.authorId(), step.createdAt());
        }
    }

    @Schema(description = "Resolución")
    record ResolutionNote(String decision, UUID by, Instant at) {
        static ResolutionNote of(Resolution resolution) {
            return new ResolutionNote(resolution.decision(), resolution.resolvedBy(), resolution.resolvedAt());
        }
    }

    @Schema(description = "Paso del seguimiento (discrepancy_responses)")
    record ResponseResource(UUID id, @Schema(example = "RESPOND") Response.Decision decision, UUID authorId,
            String comment, Instant createdAt) {
        static ResponseResource of(Response step) {
            return new ResponseResource(step.id(), step.decision(), step.authorId(), step.comment(), step.createdAt());
        }
    }

    @Schema(description = "Foto de evidencia")
    record EvidenceResource(UUID id, String url, String fileName, Instant uploadedAt) {
        static EvidenceResource of(Evidence evidence, String signedUrl) {
            return new EvidenceResource(evidence.id(), signedUrl, evidence.fileName(), evidence.uploadedAt());
        }
    }

    @Schema(description = "Discrepancia")
    record DiscrepancyResource(UUID id, UUID organizationId, UUID receptionId, UUID receptionLineId, UUID dispatchId,
            String manifestCode, UUID worksiteId, UUID warehouseId, UUID materialId, String sku, String materialName,
            String unit, DiscrepancyType type, String typeLabel, BigDecimal quantity, String reason,
            DiscrepancyStatus status, String statusLabel, UUID openedBy, Instant openedAt, Instant escalatedAt,
            Instant resolvedAt, ResponseNote response, EscalationNote escalation, ResolutionNote resolution,
            List<ResponseResource> responses, List<EvidenceResource> evidences) {

        static DiscrepancyResource of(UUID organizationId, Discrepancy discrepancy,
                Function<Evidence, String> signedUrl) {
            var reception = discrepancy.reception();
            var material = discrepancy.material();
            return new DiscrepancyResource(discrepancy.id(), organizationId, reception.receptionId(),
                    reception.receptionLineId(), reception.dispatchId(), reception.manifestCode(),
                    reception.worksiteId(), reception.warehouseId(), material.materialId(), material.sku(),
                    material.name(), material.unit(), discrepancy.type(), discrepancy.type().label(),
                    discrepancy.quantity(), discrepancy.reason(), discrepancy.status(), discrepancy.status().label(),
                    discrepancy.openedBy(), discrepancy.openedAt(), discrepancy.escalatedAt(),
                    discrepancy.resolvedAt(), discrepancy.response().map(ResponseNote::of).orElse(null),
                    discrepancy.escalation().map(EscalationNote::of).orElse(null),
                    discrepancy.resolution().map(ResolutionNote::of).orElse(null),
                    discrepancy.responses().stream().map(ResponseResource::of).toList(),
                    discrepancy.evidences().stream()
                            .map(evidence -> EvidenceResource.of(evidence, signedUrl.apply(evidence))).toList());
        }
    }
}
