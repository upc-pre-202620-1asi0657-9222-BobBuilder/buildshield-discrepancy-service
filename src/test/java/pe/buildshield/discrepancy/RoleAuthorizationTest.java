package pe.buildshield.discrepancy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pe.buildshield.discrepancy.application.DiscrepancyCommandService;
import pe.buildshield.discrepancy.application.DiscrepancyQueryService;
import pe.buildshield.discrepancy.domain.model.Discrepancy;
import pe.buildshield.discrepancy.domain.model.DiscrepancyType;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.shared.security.JwtTokenIssuer;
import pe.buildshield.discrepancy.shared.security.RevokedTokenStore;
import pe.buildshield.discrepancy.shared.tenant.TenantInfo;
import pe.buildshield.discrepancy.support.TestImages;
import pe.buildshield.discrepancy.support.WebSliceTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Matriz de autorización de la API de discrepancias contra anónimo y cada rol (los servicios están simulados). */
@WebMvcTest
@WebSliceTest
@org.springframework.context.annotation.Import(pe.buildshield.discrepancy.application.EvidenceUrlSigner.class)
class RoleAuthorizationTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Instant NOW = Instant.parse("2026-11-03T16:10:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenIssuer issuer;

    @MockitoBean
    DiscrepancyCommandService commands;

    @MockitoBean
    DiscrepancyQueryService queries;

    @MockitoBean
    RevokedTokenStore revokedTokens;

    @ParameterizedTest(name = "{0} {1} como {2} → {3}")
    @CsvSource({
            "POST, /api/v1/discrepancies, ANONYMOUS, 401",
            "POST, /api/v1/discrepancies, SITE_MANAGER, 201",
            "POST, /api/v1/discrepancies, WAREHOUSE_MANAGER, 403",
            "POST, /api/v1/discrepancies, ADMINISTRATOR, 403",
            "GET, /api/v1/discrepancies, ANONYMOUS, 401",
            "GET, /api/v1/discrepancies, SITE_MANAGER, 200",
            "GET, /api/v1/discrepancies, WAREHOUSE_MANAGER, 200",
            "GET, /api/v1/discrepancies, ADMINISTRATOR, 200",
            "GET, /api/v1/discrepancies/{id}, WAREHOUSE_MANAGER, 200",
            "POST, /api/v1/discrepancies/{id}/response, SITE_MANAGER, 403",
            "POST, /api/v1/discrepancies/{id}/response, WAREHOUSE_MANAGER, 200",
            "POST, /api/v1/discrepancies/{id}/response, ADMINISTRATOR, 200",
            "POST, /api/v1/discrepancies/{id}/accept, SITE_MANAGER, 200",
            "POST, /api/v1/discrepancies/{id}/accept, WAREHOUSE_MANAGER, 403",
            "POST, /api/v1/discrepancies/{id}/accept, ADMINISTRATOR, 403",
            "POST, /api/v1/discrepancies/{id}/escalate, SITE_MANAGER, 200",
            "POST, /api/v1/discrepancies/{id}/escalate, ADMINISTRATOR, 403",
            "POST, /api/v1/discrepancies/{id}/resolve, ADMINISTRATOR, 200",
            "POST, /api/v1/discrepancies/{id}/resolve, SITE_MANAGER, 403",
            "POST, /api/v1/discrepancies/{id}/resolve, WAREHOUSE_MANAGER, 403",
            "UPLOAD, /api/v1/discrepancies/{id}/evidences, SITE_MANAGER, 201",
            "UPLOAD, /api/v1/discrepancies/{id}/evidences, WAREHOUSE_MANAGER, 403",
            "UPLOAD, /api/v1/discrepancies/{id}/evidences, ANONYMOUS, 401",
            "GET, /api/v1/discrepancies/{id}/evidences/{id}/content, ADMINISTRATOR, 200",
            "GET, /api/v1/discrepancies/{id}/evidences/{id}/content, ANONYMOUS, 200"
    })
    void each_endpoint_answers_by_role(String method, String path, String role, int expected) throws Exception {
        Discrepancy discrepancy = sample();
        Evidence evidence = new Evidence(ID, "k", "/u", "foto.jpg", "image/jpeg", 10, ID, NOW);
        when(commands.create(any())).thenReturn(discrepancy);
        when(commands.respond(any(), any())).thenReturn(discrepancy);
        when(commands.accept(any())).thenReturn(discrepancy);
        when(commands.escalate(any(), any())).thenReturn(discrepancy);
        when(commands.resolve(any(), any())).thenReturn(discrepancy);
        when(commands.addEvidence(any(), any())).thenReturn(evidence);
        when(queries.list(any(), any(), any())).thenReturn(List.of(discrepancy));
        when(queries.get(any())).thenReturn(discrepancy);
        when(queries.evidenceWithToken(any(), any(), any())).thenReturn(new DiscrepancyQueryService.EvidenceFile(
                evidence, TestImages.JPEG));

        String url = path.replace("{id}", ID.toString());
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(url);
            case "UPLOAD" -> multipart(url).file(new MockMultipartFile("file", "foto.jpg", "image/jpeg",
                    TestImages.JPEG));
            default -> post(url).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",
                    UUID.randomUUID().toString()).content(body(url));
        };
        if (!role.equals("ANONYMOUS")) {
            request.header("Authorization", "Bearer " + issuer.issue(new TenantInfo(UUID.randomUUID(),
                    UUID.randomUUID(), role)).value());
        }
        mvc.perform(request).andExpect(status().is(expected));
    }

    private static String body(String url) {
        if (url.endsWith("/discrepancies")) {
            return "{\"receptionId\":\"" + ID + "\",\"materialId\":\"" + ID
                    + "\",\"type\":\"DAMAGE\",\"quantity\":2,\"observation\":\"Bolsas rotas\"}";
        }
        return "{\"text\":\"Texto\",\"reason\":\"Motivo\",\"decision\":\"Decisión\"}";
    }

    private static Discrepancy sample() {
        return Discrepancy.restore(ID, new Discrepancy.ReceptionLink(ID, null, ID, "MAN-1", ID, ID),
                new Discrepancy.MaterialLink(ID, "CEM-001", "Cemento"), DiscrepancyType.DAMAGE, BigDecimal.ONE, null,
                BigDecimal.ONE, "Obs", pe.buildshield.discrepancy.domain.model.DiscrepancyOrigin.MANUAL,
                pe.buildshield.discrepancy.domain.model.DiscrepancyStatus.OPEN, ID, NOW, null, null, null, null,
                List.of(), 0L);
    }
}
