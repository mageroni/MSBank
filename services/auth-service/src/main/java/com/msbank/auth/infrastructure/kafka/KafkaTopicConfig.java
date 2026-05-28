package com.msbank.auth.infrastructure.kafka;

import com.msbank.auth.config.AuthProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Auto-creates the user-events topic during local development. */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic userEventsTopic(AuthProperties props) {
        return new NewTopic(props.kafka().userEventsTopic(), 3, (short) 1);
    }
}
