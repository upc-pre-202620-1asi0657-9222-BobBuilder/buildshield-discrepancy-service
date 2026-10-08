package pe.buildshield.discrepancy.application;

import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyFilter;
import pe.buildshield.discrepancy.domain.model.DiscrepancyRepository;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;
import pe.buildshield.discrepancy.domain.model.LogisticsCopies;
import pe.buildshield.discrepancy.domain.model.MaterialSnapshot;
import pe.buildshield.discrepancy.domain.model.ReceptionSnapshot;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Repositorio, copias locales y almacenamiento en memoria, con el filtro por organización de la persistencia real. */
class InMemoryDiscrepancies implements DiscrepancyRepository, LogisticsCopies, EvidenceStorage {

    final Map<UUID, Discrepancy> byId = new HashMap<>();
    final Map<UUID, UUID> organizationOf = new HashMap<>();
    final Map<UUID, MaterialSnapshot> materials = new HashMap<>();
    final Map<UUID, Object[]> assignments = new HashMap<>();
    final Map<UUID, ReceptionSnapshot> receptions = new HashMap<>();
    final Map<String, byte[]> files = new HashMap<>();

    private UUID organization() {
        return TenantContext.require().organizationId();
    }

    @Override
    public Discrepancy save(Discrepancy d) {
        UUID id = d.id() == null ? UUID.randomUUID() : d.id();
        Discrepancy saved = Discrepancy.restore(id, d.reception(), d.material(), d.type(), d.quantity(),
                d.shrinkagePercent(), d.tolerancePercent(), d.observation(), d.origin(), d.status(), d.openedBy(),
                d.openedAt(), d.response(), d.escalation(), d.resolution(), d.acceptance(), d.evidences(),
                d.version() == null ? 0L : d.version() + 1);
        byId.put(id, saved);
        organizationOf.put(id, organization());
        return saved;
    }

    @Override
    public Optional<Discrepancy> findById(UUID id) {
        return Optional.ofNullable(byId.get(id)).filter(d -> organization().equals(organizationOf.get(id)));
    }

    @Override
    public boolean existsForReceptionLine(UUID receptionLineId) {
        return byId.values().stream().anyMatch(d -> receptionLineId.equals(d.reception().receptionLineId()));
    }

    @Override
    public boolean existsUnclosed(UUID receptionId, UUID materialId, DiscrepancyType type) {
        return byId.values().stream().anyMatch(d -> d.reception().receptionId().equals(receptionId)
                && d.material().materialId().equals(materialId) && d.type() == type && !d.status().closed());
    }

    @Override
    public List<Discrepancy> search(DiscrepancyFilter filter) {
        return byId.values().stream()
                .filter(d -> organization().equals(organizationOf.get(d.id())))
                .filter(d -> filter.status() == null || d.status() == filter.status())
                .filter(d -> filter.worksiteId() == null || d.reception().worksiteId().equals(filter.worksiteId()))
                .filter(d -> filter.dispatchId() == null || d.reception().dispatchId().equals(filter.dispatchId()))
                .filter(d -> filter.worksiteScope() == null || filter.worksiteScope().contains(d.reception().worksiteId()))
                .filter(d -> filter.warehouseScope() == null
                        || filter.warehouseScope().contains(d.reception().warehouseId()))
                .sorted(Comparator.comparing(Discrepancy::openedAt).reversed())
                .toList();
    }

    @Override
    public void upsertMaterial(UUID organizationId, MaterialSnapshot material, boolean active) {
        materials.put(material.materialId(), material);
    }

    @Override
    public Optional<MaterialSnapshot> material(UUID organizationId, UUID materialId) {
        return Optional.ofNullable(materials.get(materialId));
    }

    @Override
    public void assign(UUID organizationId, UUID assignmentId, UUID userId, UUID locationId, String locationType) {
        assignments.put(assignmentId, new Object[]{organizationId, userId, locationId, locationType});
    }

    @Override
    public void unassign(UUID assignmentId) {
        assignments.remove(assignmentId);
    }

    @Override
    public Set<UUID> assignedLocations(UUID organizationId, UUID userId, String locationType) {
        Set<UUID> found = new HashSet<>();
        for (Object[] a : assignments.values()) {
            if (a[0].equals(organizationId) && a[1].equals(userId) && a[3].equals(locationType)) {
                found.add((UUID) a[2]);
            }
        }
        return found;
    }

    @Override
    public void upsertReception(UUID organizationId, ReceptionSnapshot reception) {
        ReceptionSnapshot known = receptions.get(reception.receptionId());
        List<ReceptionSnapshot.Line> lines = new ArrayList<>(known == null ? List.of() : known.lines());
        for (ReceptionSnapshot.Line line : reception.lines()) {
            lines.removeIf(existing -> existing.receptionLineId().equals(line.receptionLineId()));
            lines.add(line);
        }
        receptions.put(reception.receptionId(), new ReceptionSnapshot(reception.receptionId(), reception.dispatchId(),
                reception.manifestCode() != null ? reception.manifestCode()
                        : known == null ? null : known.manifestCode(),
                reception.worksiteId(), reception.warehouseId(),
                reception.confirmed() || (known != null && known.confirmed()), lines));
    }

    @Override
    public Optional<ReceptionSnapshot> reception(UUID organizationId, UUID receptionId) {
        return Optional.ofNullable(receptions.get(receptionId));
    }

    @Override
    public String store(String key, String contentType, byte[] content) {
        files.put(key, content);
        return key;
    }

    @Override
    public Optional<byte[]> load(String key) {
        return Optional.ofNullable(files.get(key));
    }

    long count(DiscrepancyStatus status) {
        return byId.values().stream().filter(d -> d.status() == status).count();
    }
}
