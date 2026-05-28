package com.msbank.auth.api;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Liveness/readiness probes that complement Spring Boot's actuator. */
@RestController
public class HealthController {

    private final HealthEndpoint healthEndpoint;

    public HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/healthz")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    /** Readiness reflects health of all auto-configured indicators (DB + Kafka). */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> ready() {
        Status status = healthEndpoint.health().getStatus();
        boolean up = Status.UP.equals(status);
        return ResponseEntity.status(up ? 200 : 503).body(Map.of("status", up ? "READY" : "NOT_READY"));
    }
}
