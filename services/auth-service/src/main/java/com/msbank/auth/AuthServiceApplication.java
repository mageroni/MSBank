package com.msbank.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auth-service entry point. Issues JWT access/refresh tokens, manages
 * users, MFA, and publishes lifecycle events via the transactional outbox.
 */
@SpringBootApplication
@EnableScheduling
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
