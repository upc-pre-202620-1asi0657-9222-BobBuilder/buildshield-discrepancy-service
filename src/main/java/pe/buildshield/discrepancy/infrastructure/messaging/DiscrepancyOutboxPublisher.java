package pe.buildshield.discrepancy.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.discrepancy.shared.config.BuildshieldProperties;
import pe.buildshield.discrepancy.shared.messaging.OutboxPublisher;

import java.time.Clock;

/**
 * Productor Kafka de discrepancy: cada 500 ms envía a {@code buildshield.discrepancy} los eventos pendientes del
 * outbox ({@code DiscrepancyOpened}, {@code DiscrepancyResponded}, {@code DiscrepancyAccepted},
 * {@code DiscrepancyEscalated}, {@code DiscrepancyResolved}).
 */
@Component
public class DiscrepancyOutboxPublisher extends OutboxPublisher {

    public DiscrepancyOutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper json,
            PlatformTransactionManager transactionManager, Clock clock, BuildshieldProperties properties) {
        super(jdbc, kafka, json, new TransactionTemplate(transactionManager), clock,
                properties.getMessaging().getTopic(), properties.getMessaging().getBatchSize(),
                properties.getMessaging().getSendTimeout());
    }

    @Scheduled(fixedDelayString = "${buildshield.messaging.outbox-interval:PT0.5S}")
    public void publish() {
        publishPending();
    }
}
