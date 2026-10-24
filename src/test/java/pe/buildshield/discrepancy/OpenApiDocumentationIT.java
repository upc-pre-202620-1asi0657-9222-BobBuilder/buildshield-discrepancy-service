package pe.buildshield.discrepancy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import pe.buildshield.discrepancy.support.DiscrepancyIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La especificación OpenAPI documenta el bloque «Discrepancias» con la cabecera Idempotency-Key obligatoria. */
@DiscrepancyIntegrationTest
@AutoConfigureMockMvc
class OpenApiDocumentationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void api_docs_describe_the_discrepancy_endpoints_and_the_idempotency_key() throws Exception {
        JsonNode spec = json.readTree(mvc.perform(get("/api/v1/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(spec.path("paths").fieldNames()).toIterable().contains("/api/v1/discrepancies",
                "/api/v1/discrepancies/{id}", "/api/v1/discrepancies/{id}/evidences",
                "/api/v1/discrepancies/{id}/response", "/api/v1/discrepancies/{id}/accept",
                "/api/v1/discrepancies/{id}/escalate", "/api/v1/discrepancies/{id}/resolve",
                "/api/v1/discrepancies/{id}/evidences/{evidenceId}/content");
        assertThat(spec.at("/paths/~1api~1v1~1discrepancies/post").isMissingNode()).isTrue();
        JsonNode respond = spec.at("/paths/~1api~1v1~1discrepancies~1{id}~1response/post");
        assertThat(respond.path("parameters").toString()).contains("Idempotency-Key");
        assertThat(spec.at("/paths/~1api~1v1~1discrepancies~1{id}~1evidences/post/requestBody/content")
                .has("multipart/form-data")).isTrue();
    }
}
