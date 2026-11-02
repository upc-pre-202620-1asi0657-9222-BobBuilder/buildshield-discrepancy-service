package pe.buildshield.discrepancy.infrastructure.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;

/** Cliente de S3. La región y las credenciales salen del entorno: {@code AWS_REGION} y el rol de la tarea de ECS. */
@Configuration
@ConditionalOnProperty(name = "buildshield.evidence.storage", havingValue = "s3")
class S3ClientConfiguration {

    @Bean(destroyMethod = "close")
    S3Client s3Client() {
        return S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build();
    }
}
