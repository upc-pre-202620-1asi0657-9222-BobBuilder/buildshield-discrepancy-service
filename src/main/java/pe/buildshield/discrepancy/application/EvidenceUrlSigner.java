package pe.buildshield.discrepancy.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pe.buildshield.discrepancy.domain.model.Evidence;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * URL firmada y de vida corta de la foto de una evidencia. La web la pone tal cual en {@code <img src>}, que no envía
 * la cabecera Authorization: el permiso viaja en {@code ?token=}, una firma HMAC-SHA256 de la organización, la
 * discrepancia, la evidencia y el vencimiento. Solo se firma para quien puede ver la discrepancia (al responder un
 * GET o la subida), así que la URL no amplía el acceso; vence en {@code EVIDENCE_URL_TTL} (30 min por defecto).
 */
@Component
public class EvidenceUrlSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    public EvidenceUrlSigner(@Value("${buildshield.evidence.url-secret:buildshield-dev-evidence-secret}") String secret,
            @Value("${buildshield.evidence.url-ttl:PT30M}") Duration ttl, Clock clock) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("buildshield.evidence.url-secret debe tener al menos 16 caracteres");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.clock = clock;
    }

    /** URL relativa (pasa por el gateway) con el token de acceso a la foto. */
    public String signedUrl(UUID organizationId, UUID discrepancyId, Evidence evidence) {
        long expires = clock.instant().plus(ttl).getEpochSecond();
        return evidence.url() + "?token=" + organizationId + "." + expires + "."
                + signature(organizationId, discrepancyId, evidence.id(), expires);
    }

    /** @return la organización del token si es válido, de esta evidencia y no venció */
    public Optional<UUID> verify(UUID discrepancyId, UUID evidenceId, String token) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            UUID organizationId = UUID.fromString(parts[0]);
            long expires = Long.parseLong(parts[1]);
            if (clock.instant().getEpochSecond() > expires) {
                return Optional.empty();
            }
            byte[] expected = signature(organizationId, discrepancyId, evidenceId, expires)
                    .getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.US_ASCII))
                    ? Optional.of(organizationId) : Optional.empty();
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String signature(UUID organizationId, UUID discrepancyId, UUID evidenceId, long expires) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] digest = mac.doFinal((organizationId + "|" + discrepancyId + "|" + evidenceId + "|" + expires)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo firmar la URL de la evidencia", ex);
        }
    }
}
