package pe.buildshield.discrepancy.application;

import org.springframework.stereotype.Component;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyFilter;
import pe.buildshield.discrepancy.domain.model.DiscrepancyStatus;
import pe.buildshield.discrepancy.domain.model.StaffAssignments;
import pe.buildshield.discrepancy.shared.tenant.TenantContext;
import pe.buildshield.discrepancy.shared.tenant.TenantInfo;

import java.util.Set;
import java.util.UUID;

/**
 * Qué discrepancias ve quien hace la petición, como en el Core: el administrador todas; el encargado de obra las de
 * sus obras asignadas; el encargado de almacén las de sus almacenes asignados. Lo que no ve se trata como
 * inexistente (404).
 */
@Component
public class DiscrepancyVisibility {

    static final String ADMINISTRATOR = "ADMINISTRATOR";
    static final String SITE_MANAGER = "SITE_MANAGER";
    static final String WAREHOUSE_MANAGER = "WAREHOUSE_MANAGER";
    static final String WORKSITE = "WORKSITE";
    static final String WAREHOUSE = "WAREHOUSE";

    private final StaffAssignments assignments;

    public DiscrepancyVisibility(StaffAssignments assignments) {
        this.assignments = assignments;
    }

    DiscrepancyFilter filter(DiscrepancyStatus status, UUID worksiteId, UUID dispatchId) {
        TenantInfo requester = TenantContext.require();
        return switch (requester.role()) {
            case ADMINISTRATOR -> new DiscrepancyFilter(status, worksiteId, dispatchId, null, null);
            case SITE_MANAGER -> new DiscrepancyFilter(status, worksiteId, dispatchId, assigned(requester, WORKSITE),
                    null);
            case WAREHOUSE_MANAGER -> new DiscrepancyFilter(status, worksiteId, dispatchId, null,
                    assigned(requester, WAREHOUSE));
            default -> new DiscrepancyFilter(status, worksiteId, dispatchId, Set.of(), Set.of());
        };
    }

    boolean canSee(Discrepancy discrepancy) {
        return canSeeWorksiteOrWarehouse(discrepancy.reception().worksiteId(), discrepancy.reception().warehouseId());
    }

    boolean canSeeWorksiteOrWarehouse(UUID worksiteId, UUID warehouseId) {
        TenantInfo requester = TenantContext.require();
        return switch (requester.role()) {
            case ADMINISTRATOR -> true;
            case SITE_MANAGER -> assigned(requester, WORKSITE).contains(worksiteId);
            case WAREHOUSE_MANAGER -> assigned(requester, WAREHOUSE).contains(warehouseId);
            default -> false;
        };
    }

    private Set<UUID> assigned(TenantInfo requester, String type) {
        return assignments.assignedLocations(requester.organizationId(), requester.userId(), type);
    }
}
