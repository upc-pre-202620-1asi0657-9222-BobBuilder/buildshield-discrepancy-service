package pe.buildshield.discrepancy.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyFilter;
import pe.buildshield.discrepancy.domain.model.DiscrepancyRepository;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.shared.error.ResourceNotFoundException;
import pe.buildshield.discrepancy.shared.messaging.IdempotentEventProcessor;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;
import pe.buildshield.discrepancy.shared.tenant.TenantInfo;

import java.util.List;
import java.util.UUID;

/** Consultas de discrepancias filtradas por rol (US36). */
@Service
public class DiscrepancyQueryService {

    private final DiscrepancyRepository discrepancies;
    private final DiscrepancyVisibility visibility;
    private final EvidenceStorage storage;
    private final EvidenceUrlSigner signer;
    private final TransactionTemplate transactions;

    public DiscrepancyQueryService(DiscrepancyRepository discrepancies, DiscrepancyVisibility visibility,
            EvidenceStorage storage, EvidenceUrlSigner signer, PlatformTransactionManager transactionManager) {
        this.discrepancies = discrepancies;
        this.visibility = visibility;
        this.storage = storage;
        this.signer = signer;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
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

    /**
     * Archivo de una evidencia pedido con la URL firmada (sin cabecera Authorization, desde {@code <img>}). Token
     * inválido, vencido o de otra evidencia: 404, sin revelar si existe. La organización del token se fija antes de
     * abrir la transacción para que el filtro multiempresa de Hibernate la use.
     */
    public EvidenceFile evidenceWithToken(UUID discrepancyId, UUID evidenceId, String token) {
        UUID organizationId = signer.verify(discrepancyId, evidenceId, token).orElseThrow(() ->
                new ResourceNotFoundException("EVIDENCE_NOT_FOUND", "La evidencia " + evidenceId + " no existe"));
        TenantInfo reader = new TenantInfo(organizationId, TenantContext.SYSTEM_USER_ID,
                IdempotentEventProcessor.SYSTEM_ROLE);
        return TenantContext.callAs(reader, () -> transactions.execute(status -> {
            Evidence evidence = discrepancies.findById(discrepancyId).stream()
                    .flatMap(found -> found.evidences().stream())
                    .filter(found -> found.id().equals(evidenceId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("EVIDENCE_NOT_FOUND",
                            "La evidencia " + evidenceId + " no existe"));
            byte[] content = storage.load(evidence.storageKey()).orElseThrow(() -> new ResourceNotFoundException(
                    "EVIDENCE_NOT_FOUND", "El archivo de la evidencia no está disponible"));
            return new EvidenceFile(evidence, content);
        }));
    }

    public record EvidenceFile(Evidence evidence, byte[] content) {
    }
}
