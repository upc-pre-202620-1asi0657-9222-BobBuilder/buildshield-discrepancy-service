package pe.buildshield.discrepancy.shared.idempotency;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcIdempotencyStoreTest {

    @Test
    void lock_id_is_stable_and_depends_on_organization_and_key() {
        UUID organization = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        assertThat(JdbcIdempotencyStore.lockId(organization, key)).isEqualTo(JdbcIdempotencyStore.lockId(organization, key));
        assertThat(JdbcIdempotencyStore.lockId(organization, key)).isNotEqualTo(JdbcIdempotencyStore.lockId(UUID.randomUUID(), key));
        assertThat(JdbcIdempotencyStore.lockId(organization, key)).isNotEqualTo(JdbcIdempotencyStore.lockId(organization, UUID.randomUUID()));
    }

    @Test
    void record_matches_only_the_same_method_and_path() {
        IdempotencyRecord record = new IdempotencyRecord(UUID.randomUUID(), UUID.randomUUID(), "POST",
                "/api/v1/receptions", 201, "application/json", new byte[0], Instant.now());

        assertThat(record.isSameRequest("POST", "/api/v1/receptions")).isTrue();
        assertThat(record.isSameRequest("PUT", "/api/v1/receptions")).isFalse();
        assertThat(record.isSameRequest("POST", "/api/v1/orders")).isFalse();
    }

    @Test
    void endpoint_is_method_and_path_and_must_fit_in_80_characters() {
        assertThat(JdbcIdempotencyStore.endpoint("POST", "/api/v1/discrepancies/" + UUID.randomUUID() + "/response"))
                .startsWith("POST /api/v1/discrepancies/").hasSizeLessThanOrEqualTo(80);
        assertThatThrownBy(() -> JdbcIdempotencyStore.endpoint("POST", "/" + "x".repeat(80)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void response_body_keeps_json_text_as_is_and_binary_in_base64() {
        UUID user = UUID.randomUUID();
        IdempotencyRecord json = new IdempotencyRecord(UUID.randomUUID(), UUID.randomUUID(), "POST", "/p", 200,
                "application/json", "{\"b\":1,\"a\":2}".getBytes(), Instant.now(), user, "SITE_MANAGER",
                "f".repeat(64), "/loc", false);
        assertThat(JdbcIdempotencyStore.responseBody(json)).contains("\"body\":\"{\\\"b\\\":1,\\\"a\\\":2}\"")
                .contains("\"userId\":\"" + user + "\"").contains("\"location\":\"/loc\"");
        IdempotencyRecord binary = new IdempotencyRecord(UUID.randomUUID(), UUID.randomUUID(), "POST", "/p", 201,
                "image/png", new byte[]{1, 2}, Instant.now(), user, "SITE_MANAGER", "f".repeat(64), null, false);
        assertThat(JdbcIdempotencyStore.responseBody(binary)).contains("\"bodyBase64\":\"AQI=\"");
        IdempotencyRecord empty = new IdempotencyRecord(UUID.randomUUID(), UUID.randomUUID(), "POST", "/p", 204,
                null, null, Instant.now(), null, null, "f".repeat(64), null, false);
        assertThat(JdbcIdempotencyStore.responseBody(empty)).doesNotContain("body");
    }
}
