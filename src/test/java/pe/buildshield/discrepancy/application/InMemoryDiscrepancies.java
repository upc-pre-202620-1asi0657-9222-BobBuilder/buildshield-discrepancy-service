package pe.buildshield.discrepancy.application;

import pe.buildshield.discrepancy.domain.model.DiscrepanciesRepository;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyFilter;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.StaffAssignments;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Repositorio, asignaciones y almacenamiento en memoria, con el filtro por organización de la persistencia real. */
class InMemoryDiscrepancies implements DiscrepanciesRepository, StaffAssignments, EvidenceStorage {

    final Map<UUID, Discrepancy> byId = new HashMap<>();
    final Map<UUID, UUID> organizationOf = new HashMap<>();
    final Map<UUID, Object[]> assignments = new HashMap<>();
    final Map<String, byte[]> files = new HashMap<>();

    private UUID organization() {
        return TenantContext.require().organizationId();
    }

    @Override
    public Discrepancy save(Discrepancy d) {
        UUID id = d.id() == null ? UUID.randomUUID() : d.id();
        Discrepancy saved = Discrepancy.restore(id, d.reception(), d.material(), d.type(), d.quantity(), d.reason(),
                d.status(), d.openedBy(), d.openedAt(), d.escalatedAt(), d.resolvedAt(), d.responses(),
                d.evidences(), d.version() == null ? 0L : d.version() + 1);
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
