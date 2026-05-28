package com.msbank.auth.infrastructure.kafka;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.Collections;

/** Readiness indicator that pokes Kafka via the admin client. */
@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaAdmin admin;

    public KafkaHealthIndicator(KafkaAdmin admin) {
        this.admin = admin;
    }

    @Override
    public Health health() {
        try (org.apache.kafka.clients.admin.AdminClient client =
                     org.apache.kafka.clients.admin.AdminClient.create(admin.getConfigurationProperties())) {
            client.describeCluster().clusterId().get(2, java.util.concurrent.TimeUnit.SECONDS);
            return Health.up().withDetails(Collections.emptyMap()).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
