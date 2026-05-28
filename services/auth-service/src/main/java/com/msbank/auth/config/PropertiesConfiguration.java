package com.msbank.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables binding of {@link AuthProperties} from application.yml. */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class PropertiesConfiguration {
}
