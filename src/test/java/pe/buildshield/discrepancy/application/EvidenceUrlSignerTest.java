package pe.buildshield.discrepancy.application;

import org.junit.jupiter.api.Test;
import pe.buildshield.discrepancy.domain.model.Evidence;
import pe.buildshield.discrepancy.support.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceUrlSignerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-03T16:10:00Z"));
    private final EvidenceUrlSigner signer = new EvidenceUrlSigner("secreto-de-pruebas-123", Duration.ofMinutes(30),
            clock);
    private final UUID organization = UUID.randomUUID();
    private final UUID discrepancy = UUID.randomUUID();
    private final Evidence evidence = new Evidence(UUID.randomUUID(), "k", "/api/v1/discrepancies/d/evidences/e/content",
            "foto.jpg", "image/jpeg", 10, UUID.randomUUID(), clock.instant());

    @Test
    void a_signed_url_is_valid_only_for_its_evidence_until_it_expires() {
        String token = token(signer.signedUrl(organization, discrepancy, evidence));

        assertThat(signer.verify(discrepancy, evidence.id(), token)).contains(organization);
        assertThat(signer.verify(UUID.randomUUID(), evidence.id(), token)).isEmpty();
        assertThat(signer.verify(discrepancy, UUID.randomUUID(), token)).isEmpty();
        String otherOrganization = UUID.randomUUID() + token.substring(token.indexOf('.'));
        assertThat(signer.verify(discrepancy, evidence.id(), otherOrganization)).isEmpty();
        assertThat(signer.verify(discrepancy, evidence.id(), token + "x")).isEmpty();

        clock.advance(Duration.ofMinutes(31));
        assertThat(signer.verify(discrepancy, evidence.id(), token)).isEmpty();
    }

    @Test
    void malformed_tokens_and_short_secrets_are_rejected() {
        assertThat(signer.verify(discrepancy, evidence.id(), null)).isEmpty();
        assertThat(signer.verify(discrepancy, evidence.id(), "a.b")).isEmpty();
        assertThat(signer.verify(discrepancy, evidence.id(), "no-uuid.123.sig")).isEmpty();
        assertThat(signer.verify(discrepancy, evidence.id(), organization + ".nan.sig")).isEmpty();
        assertThatThrownBy(() -> new EvidenceUrlSigner("corto", Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String token(String url) {
        return url.substring(url.indexOf("?token=") + 7);
    }
}
