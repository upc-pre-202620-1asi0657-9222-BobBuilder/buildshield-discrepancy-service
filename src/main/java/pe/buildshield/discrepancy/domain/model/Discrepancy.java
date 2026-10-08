package pe.buildshield.discrepancy.domain.model;

import pe.buildshield.discrepancy.shared.error.ConflictException;
import pe.buildshield.discrepancy.shared.error.ErrorDetail;
import pe.buildshield.discrepancy.shared.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Discrepancia de una recepción (US36): una diferencia de un material entre lo despachado y lo recibido, con su
 * evidencia, la respuesta del almacén y el cierre. Transiciones: {@code OPEN → RESPONDED → ACCEPTED} o
 * {@code RESPONDED → ESCALATED → RESOLVED}. Una transición inválida es un conflicto (409).
 */
public class Discrepancy {

    public static final String INVALID_TRANSITION = "INVALID_DISCREPANCY_TRANSITION";

    private final UUID id;
    private final ReceptionLink reception;
    private final MaterialLink material;
    private final DiscrepancyType type;
    private final BigDecimal quantity;
    private final BigDecimal shrinkagePercent;
    private final BigDecimal tolerancePercent;
    private final String observation;
    private final DiscrepancyOrigin origin;
    private DiscrepancyStatus status;
    private final UUID openedBy;
    private final Instant openedAt;
    private Note response;
    private Note escalation;
    private Note resolution;
    private Note acceptance;
    private final List<Evidence> evidences;
    private final Long version;

    /** Recepción, despacho y lugares a los que pertenece la discrepancia. */
    public record ReceptionLink(UUID receptionId, UUID receptionLineId, UUID dispatchId, String manifestCode,
            UUID worksiteId, UUID warehouseId) {

        public ReceptionLink {
            Objects.requireNonNull(receptionId, "receptionId");
            Objects.requireNonNull(dispatchId, "dispatchId");
            Objects.requireNonNull(worksiteId, "worksiteId");
            Objects.requireNonNull(warehouseId, "warehouseId");
            manifestCode = manifestCode == null || manifestCode.isBlank() ? dispatchId.toString().substring(0, 8)
                    : manifestCode;
        }
    }

    /** Material con el SKU y el nombre que tenía al abrirse la discrepancia. */
    public record MaterialLink(UUID materialId, String sku, String name) {

        public MaterialLink {
            Objects.requireNonNull(materialId, "materialId");
            sku = sku == null || sku.isBlank() ? "SIN-SKU" : sku;
            name = name == null || name.isBlank() ? "Material " + materialId.toString().substring(0, 8) : name;
        }
    }

    private Discrepancy(UUID id, ReceptionLink reception, MaterialLink material, DiscrepancyType type,
            BigDecimal quantity, BigDecimal shrinkagePercent, BigDecimal tolerancePercent, String observation,
            DiscrepancyOrigin origin, DiscrepancyStatus status, UUID openedBy, Instant openedAt, Note response,
            Note escalation, Note resolution, Note acceptance, List<Evidence> evidences, Long version) {
        this.id = id;
        this.reception = Objects.requireNonNull(reception, "reception");
        this.material = Objects.requireNonNull(material, "material");
        this.type = Objects.requireNonNull(type, "type");
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        this.shrinkagePercent = shrinkagePercent;
        this.tolerancePercent = tolerancePercent == null ? BigDecimal.ZERO : tolerancePercent;
        this.observation = Objects.requireNonNull(observation, "observation");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.status = Objects.requireNonNull(status, "status");
        this.openedBy = Objects.requireNonNull(openedBy, "openedBy");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.response = response;
        this.escalation = escalation;
        this.resolution = resolution;
        this.acceptance = acceptance;
        this.evidences = new ArrayList<>(evidences == null ? List.of() : evidences);
        this.version = version;
    }

    /**
     * Abre una discrepancia por una línea fuera de tolerancia al confirmar la recepción: faltante si llegó menos de
     * lo despachado, exceso si llegó más; la cantidad es la diferencia absoluta.
     */
    public static Discrepancy openAutomatic(ReceptionLink reception, MaterialLink material, BigDecimal dispatched,
            BigDecimal received, BigDecimal shrinkagePercent, BigDecimal tolerancePercent, UUID openedBy,
            Instant now) {
        BigDecimal difference = received.subtract(dispatched);
        DiscrepancyType type = difference.signum() > 0 ? DiscrepancyType.EXCESS : DiscrepancyType.SHORTAGE;
        String observation = "Despachado " + plain(dispatched) + ", recibido " + plain(received)
                + (shrinkagePercent == null ? "" : ": merma de " + plain(shrinkagePercent) + " %")
                + (tolerancePercent == null ? "" : " sobre una tolerancia de " + plain(tolerancePercent) + " %");
        return new Discrepancy(null, reception, material, type, positive(difference.abs()), shrinkagePercent,
                tolerancePercent, observation, DiscrepancyOrigin.AUTOMATIC, DiscrepancyStatus.OPEN, openedBy, now,
                null, null, null, null, List.of(), null);
    }

    /** Registra a mano otra diferencia de la recepción (por ejemplo, un daño dentro de la tolerancia). */
    public static Discrepancy openManual(ReceptionLink reception, MaterialLink material, DiscrepancyType type,
            BigDecimal quantity, BigDecimal shrinkagePercent, BigDecimal tolerancePercent, String observation,
            UUID openedBy, Instant now) {
        if (type == null) {
            throw new ValidationException("INVALID_DISCREPANCY_TYPE", "El tipo de discrepancia es obligatorio",
                    List.of(new ErrorDetail("type", "obligatorio")));
        }
        return new Discrepancy(null, reception, material, type, positive(quantity), shrinkagePercent, tolerancePercent,
                Texts.require(observation, "observation"), DiscrepancyOrigin.MANUAL, DiscrepancyStatus.OPEN, openedBy,
                now, null, null, null, null, List.of(), null);
    }

    public static Discrepancy restore(UUID id, ReceptionLink reception, MaterialLink material, DiscrepancyType type,
            BigDecimal quantity, BigDecimal shrinkagePercent, BigDecimal tolerancePercent, String observation,
            DiscrepancyOrigin origin, DiscrepancyStatus status, UUID openedBy, Instant openedAt, Note response,
            Note escalation, Note resolution, Note acceptance, List<Evidence> evidences, Long version) {
        return new Discrepancy(Objects.requireNonNull(id, "id"), reception, material, type, quantity, shrinkagePercent,
                tolerancePercent, observation, origin, status, openedBy, openedAt, response, escalation, resolution,
                acceptance, evidences, version);
    }

    /** El almacén responde (solo abierta). */
    public void respond(String text, UUID by, Instant now) {
        require(DiscrepancyStatus.OPEN, "responder");
        response = new Note(Texts.require(text, "text"), by, now);
        status = DiscrepancyStatus.RESPONDED;
    }

    /** La obra acepta la respuesta y cierra (solo respondida). */
    public void accept(UUID by, Instant now) {
        require(DiscrepancyStatus.RESPONDED, "aceptar");
        acceptance = new Note("Aceptada", by, now);
        status = DiscrepancyStatus.ACCEPTED;
    }

    /** La obra no acepta la respuesta y la escala al administrador (solo respondida). */
    public void escalate(String reason, UUID by, Instant now) {
        require(DiscrepancyStatus.RESPONDED, "escalar");
        escalation = new Note(Texts.require(reason, "reason"), by, now);
        status = DiscrepancyStatus.ESCALATED;
    }

    /** El administrador registra la decisión final y cierra (solo escalada). */
    public void resolve(String decision, UUID by, Instant now) {
        require(DiscrepancyStatus.ESCALATED, "resolver");
        resolution = new Note(Texts.require(decision, "decision"), by, now);
        status = DiscrepancyStatus.RESOLVED;
    }

    /** Agrega una foto de evidencia mientras la discrepancia no esté cerrada. */
    public void addEvidence(Evidence evidence) {
        if (status.closed()) {
            throw new ConflictException(INVALID_TRANSITION, "La discrepancia está cerrada: ya no admite evidencias");
        }
        evidences.add(Objects.requireNonNull(evidence, "evidence"));
    }

    private void require(DiscrepancyStatus expected, String action) {
        if (status != expected) {
            throw new ConflictException(INVALID_TRANSITION, "No se puede " + action + " una discrepancia "
                    + status.label().toLowerCase(java.util.Locale.ROOT));
        }
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

    public BigDecimal shrinkagePercent() {
        return shrinkagePercent;
    }

    public BigDecimal tolerancePercent() {
        return tolerancePercent;
    }

    public String observation() {
        return observation;
    }

    public DiscrepancyOrigin origin() {
        return origin;
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

    public Note response() {
        return response;
    }

    public Note escalation() {
        return escalation;
    }

    public Note resolution() {
        return resolution;
    }

    public Note acceptance() {
        return acceptance;
    }

    public List<Evidence> evidences() {
        return Collections.unmodifiableList(evidences);
    }

    public Long version() {
        return version;
    }
}
