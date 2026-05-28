package com.msbank.auth.infrastructure.kafka;

import com.msbank.auth.config.AuthProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wraps {@link KafkaTemplate#send} with Resilience4j retry and circuit breaker. Pulled out
 * of {@code OutboxPoller} so the annotations are honoured by Spring AOP.
 */
@Component
public class KafkaEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final AuthProperties props;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, AuthProperties props) {
        this.kafka = kafka;
        this.props = props;
    }

    @CircuitBreaker(name = "kafka-publisher")
    @Retry(name = "kafka-publisher")
    public void publish(String key, String payload) throws ExecutionException, InterruptedException, TimeoutException {
        kafka.send(props.kafka().userEventsTopic(), key, payload).get(10, TimeUnit.SECONDS);
    }
}
