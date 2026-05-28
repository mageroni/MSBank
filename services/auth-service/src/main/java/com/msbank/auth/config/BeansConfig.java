package com.msbank.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean wiring for shared utilities. */
@Configuration
public class BeansConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Fallback no-op tracer when no Micrometer tracer bean is published. */
    @Bean
    @ConditionalOnMissingBean(Tracer.class)
    public Tracer noopTracer() {
        return io.micrometer.tracing.Tracer.NOOP;
    }
}
