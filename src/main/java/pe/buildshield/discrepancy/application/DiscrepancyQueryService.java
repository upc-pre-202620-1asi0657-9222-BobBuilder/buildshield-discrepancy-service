package pe.buildshield.discrepancy.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyFilter;
import pe.buildshield.discrepancy.domain.model.DiscrepancyRepository;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.shared.error.ResourceNotFoundException;

import java.util.List;
import java.util.UUID;

/** Consultas de discrepancias filtradas por rol (US36). */
@Service
public class DiscrepancyQueryService {

    private final DiscrepancyRepository discrepancies;
    private final DiscrepancyVisibility visibility;
    private final EvidenceStorage storage;

    public DiscrepancyQueryService(DiscrepancyRepository discrepancies, DiscrepancyVisibility visibility,
            EvidenceStorage storage) {
        this.discrepancies = discrepancies;
        this.visibility = visibility;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public List<Discrepancy> list(DiscrepancyStatus status, UUID worksiteId, UUID dispatchId) {
        DiscrepancyFilter filter = visibility.filter(status, worksiteId, dispatchId);
        return filter.seesNothing() ? List.of() : discrepancies.search(filter);
    }

    @Transactional(readOnly = true)
    public Discrepancy get(UUID id) {
        return discrepancies.findById(id).filter(visibility::canSee).orElseThrow(() ->
                new ResourceNotFoundException("DISCREPANCY_NOT_FOUND", "La discrepancia " + id + " no existe"));
    }

    /** Archivo de una evidencia de una discrepancia visible. */
    @Transactional(readOnly = true)
    public EvidenceFile evidence(UUID discrepancyId, UUID evidenceId) {
        Evidence evidence = get(discrepancyId).evidences().stream()
                .filter(found -> found.id().equals(evidenceId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("EVIDENCE_NOT_FOUND",
                        "La evidencia " + evidenceId + " no existe"));
        byte[] content = storage.load(evidence.storageKey()).orElseThrow(() ->
                new ResourceNotFoundException("EVIDENCE_NOT_FOUND", "El archivo de la evidencia no está disponible"));
        return new EvidenceFile(evidence, content);
    }

    public record EvidenceFile(Evidence evidence, byte[] content) {
    }
}
