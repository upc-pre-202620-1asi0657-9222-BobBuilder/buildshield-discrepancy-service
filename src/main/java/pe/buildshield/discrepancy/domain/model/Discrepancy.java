package pe.buildshield.discrepancy.domain.model;

import pe.buildshield.discrepancy.shared.error.ConflictException;
import pe.buildshield.discrepancy.shared.error.ErrorDetail;
import pe.buildshield.discrepancy.shared.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Discrepancia de una línea de recepción fuera de tolerancia (US36): faltante, sobrante o merma de un material entre
 * lo despachado y lo recibido, con sus evidencias y sus respuestas. Transiciones: {@code OPEN → RESPONDED → ACCEPTED}
 * o {@code RESPONDED → ESCALATED → RESOLVED}. Una transición inválida es un conflicto (409).
 */
public class Discrepancy {

    public static final String INVALID_TRANSITION = "INVALID_DISCREPANCY_TRANSITION";
    static final String ACCEPTED_COMMENT = "Respuesta aceptada";

    private final UUID id;
    private final ReceptionLink reception;
    private final MaterialLink material;
    private final DiscrepancyType type;
    private final BigDecimal quantity;
    private final String reason;
    private DiscrepancyStatus status;
    private final UUID openedBy;
    private final Instant openedAt;
    private Instant escalatedAt;
    private Instant resolvedAt;
    private final List<Response> responses;
    private final List<Evidence> evidences;
    private final Long version;

    /** Recepción, línea, despacho y lugares a los que pertenece la discrepancia. */
    public record ReceptionLink(UUID receptionId, UUID receptionLineId, UUID dispatchId, String manifestCode,
            UUID worksiteId, UUID warehouseId) {

        public ReceptionLink {
            Objects.requireNonNull(receptionId, "receptionId");
            Objects.requireNonNull(receptionLineId, "receptionLineId");
            Objects.requireNonNull(dispatchId, "dispatchId");
            Objects.requireNonNull(worksiteId, "worksiteId");
            Objects.requireNonNull(warehouseId, "warehouseId");
            manifestCode = manifestCode == null || manifestCode.isBlank() ? dispatchId.toString().substring(0, 8)
                    : manifestCode;
        }
    }

    /** Material con el SKU, el nombre y la unidad que traía la recepción. */
    public record MaterialLink(UUID materialId, String sku, String name, String unit) {

        public MaterialLink {
            Objects.requireNonNull(materialId, "materialId");
            sku = sku == null || sku.isBlank() ? "SIN-SKU" : sku;
            name = name == null || name.isBlank() ? "Material " + materialId.toString().substring(0, 8) : name;
            unit = unit == null || unit.isBlank() ? null : unit;
        }
    }

    private Discrepancy(UUID id, ReceptionLink reception, MaterialLink material, DiscrepancyType type,
            BigDecimal quantity, String reason, DiscrepancyStatus status, UUID openedBy, Instant openedAt,
            Instant escalatedAt, Instant resolvedAt, List<Response> responses, List<Evidence> evidences,
            Long version) {
        this.id = id;
        this.reception = Objects.requireNonNull(reception, "reception");
        this.material = Objects.requireNonNull(material, "material");
        this.type = Objects.requireNonNull(type, "type");
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.status = Objects.requireNonNull(status, "status");
        this.openedBy = Objects.requireNonNull(openedBy, "openedBy");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.escalatedAt = escalatedAt;
        this.resolvedAt = resolvedAt;
        this.responses = new ArrayList<>(responses == null ? List.of() : responses);
        this.responses.sort(Comparator.comparing(Response::createdAt));
        this.evidences = new ArrayList<>(evidences == null ? List.of() : evidences);
        this.version = version;
    }

    /**
     * Abre la discrepancia de una línea fuera de tolerancia al confirmar la recepción. El tipo sale de
     * {@link DiscrepancyType#of}; la cantidad es la diferencia absoluta y el motivo la resume en 60 caracteres.
     */
    public static Discrepancy open(ReceptionLink reception, MaterialLink material, BigDecimal dispatched,
            BigDecimal received, BigDecimal shrinkagePercent, BigDecimal tolerancePercent, UUID openedBy,
            Instant now) {
        DiscrepancyType type = DiscrepancyType.of(dispatched, received, tolerancePercent);
        BigDecimal difference = positive(received.subtract(dispatched).abs());
        return new Discrepancy(null, reception, material, type, difference,
                reason(type, difference, dispatched, material.unit(), shrinkagePercent, tolerancePercent),
                DiscrepancyStatus.OPEN, openedBy, now, null, null, List.of(), List.of(), null);
    }

    public static Discrepancy restore(UUID id, ReceptionLink reception, MaterialLink material, DiscrepancyType type,
            BigDecimal quantity, String reason, DiscrepancyStatus status, UUID openedBy, Instant openedAt,
            Instant escalatedAt, Instant resolvedAt, List<Response> responses, List<Evidence> evidences,
            Long version) {
        return new Discrepancy(Objects.requireNonNull(id, "id"), reception, material, type, quantity, reason, status,
                openedBy, openedAt, escalatedAt, resolvedAt, responses, evidences, version);
    }

    /** El almacén responde (solo abierta). */
    public void respond(String text, UUID by, Instant now) {
        require(DiscrepancyStatus.OPEN, "responder");
        record(Response.Decision.RESPOND, Texts.require(text, "text"), by, now);
        status = DiscrepancyStatus.RESPONDED;
    }

    /** La obra acepta la respuesta y cierra (solo respondida). */
    public void accept(UUID by, Instant now) {
        require(DiscrepancyStatus.RESPONDED, "aceptar");
        record(Response.Decision.ACCEPT, ACCEPTED_COMMENT, by, now);
        status = DiscrepancyStatus.ACCEPTED;
    }

    /** La obra no acepta la respuesta y la escala al administrador (solo respondida). */
    public void escalate(String reason, UUID by, Instant now) {
        require(DiscrepancyStatus.RESPONDED, "escalar");
        record(Response.Decision.ESCALATE, Texts.require(reason, "reason"), by, now);
        status = DiscrepancyStatus.ESCALATED;
        escalatedAt = now;
    }

    /** El administrador registra la decisión final y cierra (solo escalada). */
    public void resolve(String decision, UUID by, Instant now) {
        require(DiscrepancyStatus.ESCALATED, "resolver");
        record(Response.Decision.RESOLVE, Texts.require(decision, "decision"), by, now);
        status = DiscrepancyStatus.RESOLVED;
        resolvedAt = now;
    }

    /** Agrega una foto de evidencia mientras la discrepancia no esté cerrada. */
    public void addEvidence(Evidence evidence) {
        if (status.closed()) {
            throw new ConflictException(INVALID_TRANSITION, "La discrepancia está cerrada: ya no admite evidencias");
        }
        evidences.add(Objects.requireNonNull(evidence, "evidence"));
    }

    /** Respuesta del almacén, si ya respondió. */
    public Optional<Response> response() {
        return last(Response.Decision.RESPOND);
    }

    /** Motivo del escalamiento, si se escaló. */
    public Optional<Response> escalation() {
        return last(Response.Decision.ESCALATE);
    }

    /** Decisión final, si se resolvió. */
    public Optional<Resolution> resolution() {
        return last(Response.Decision.RESOLVE).map(step -> new Resolution(step.comment(), step.authorId(),
                step.createdAt()));
    }

    private Optional<Response> last(Response.Decision decision) {
        Response found = null;
        for (Response step : responses) {
            if (step.decision() == decision) {
                found = step;
            }
        }
        return Optional.ofNullable(found);
    }

    private void record(Response.Decision decision, String comment, UUID by, Instant now) {
        responses.add(new Response(UUID.randomUUID(), decision, by, comment, now));
    }

    private void require(DiscrepancyStatus expected, String action) {
        if (status != expected) {
            throw new ConflictException(INVALID_TRANSITION, "No se puede " + action + " una discrepancia "
                    + status.label().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static String reason(DiscrepancyType type, BigDecimal difference, BigDecimal dispatched, String unit,
            BigDecimal shrinkagePercent, BigDecimal tolerancePercent) {
        String units = unit == null ? "" : " " + unit;
        return Texts.reason(switch (type) {
            case SURPLUS -> "Sobran " + plain(difference) + units + " de " + plain(dispatched) + " despachados";
            case SHORTAGE -> "Faltan " + plain(difference) + units + " de " + plain(dispatched) + " despachados";
            case SHRINKAGE -> "Merma de " + (shrinkagePercent == null ? plain(difference) + units
                    : plain(shrinkagePercent) + " %") + " sobre " + plain(tolerancePercent) + " % tolerado";
        });
    }

    private static BigDecimal positive(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ValidationException("INVALID_QUANTITY", "La cantidad debe ser mayor que cero",
                    List.of(new ErrorDetail("quantity", "mayor que cero")));
        }
        return quantity;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public UUID id() {
        return id;
    }

    public ReceptionLink reception() {
        return reception;
    }

    public MaterialLink material() {
        return material;
    }

    public DiscrepancyType type() {
        return type;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public String reason() {
        return reason;
    }

    public DiscrepancyStatus status() {
        return status;
    }

    public UUID openedBy() {
        return openedBy;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant escalatedAt() {
        return escalatedAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    /** Pasos en orden de registro. */
    public List<Response> responses() {
        return Collections.unmodifiableList(responses);
    }

    public List<Evidence> evidences() {
        return Collections.unmodifiableList(evidences);
    }

    public Long version() {
        return version;
    }
}
