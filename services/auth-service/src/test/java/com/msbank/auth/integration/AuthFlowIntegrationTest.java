package com.msbank.auth.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msbank.auth.AuthServiceApplication;
import com.msbank.auth.domain.OutboxEvent;
import com.msbank.auth.infrastructure.jpa.OutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = AuthServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        java.nio.file.Path tmp = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "auth-service-it-" + UUID.randomUUID());
        r.add("auth.jwt.private-key-path", () -> tmp.resolve("priv.pem").toString());
        r.add("auth.jwt.public-key-path", () -> tmp.resolve("pub.pem").toString());
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired OutboxEventRepository outboxRepo;

    @Test
    void register_login_refresh_me_andOutboxPublishes() throws Exception {
        RestClient http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        String email = "alice+" + UUID.randomUUID() + "@example.com";

        // REGISTER
        Map<String, Object> regBody = Map.of(
                "email", email,
                "password", "Str0ng-Pa55word!",
                "firstName", "Alice",
                "lastName", "Anderson"
        );
        ResponseEntity<JsonNode> reg = http.post().uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).body(regBody).retrieve().toEntity(JsonNode.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reg.getBody().get("email").asText()).isEqualTo(email.toLowerCase());

        // LOGIN
        Map<String, Object> loginBody = Map.of("email", email, "password", "Str0ng-Pa55word!");
        JsonNode pair = http.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(loginBody).retrieve().body(JsonNode.class);
        String access = pair.get("accessToken").asText();
        String refresh = pair.get("refreshToken").asText();
        assertThat(access).isNotEmpty();
        assertThat(refresh).isNotEmpty();
        assertThat(pair.get("tokenType").asText()).isEqualTo("Bearer");

        // REFRESH
        JsonNode pair2 = http.post().uri("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refresh)).retrieve().body(JsonNode.class);
        String newAccess = pair2.get("accessToken").asText();
        String newRefresh = pair2.get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(refresh);

        // ME
        JsonNode me = http.get().uri("/api/v1/auth/me")
                .header("Authorization", "Bearer " + newAccess)
                .retrieve().body(JsonNode.class);
        assertThat(me.get("email").asText()).isEqualTo(email.toLowerCase());
        assertThat(me.get("roles").toString()).contains("CUSTOMER");

        // OUTBOX → KAFKA
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            long pending = outboxRepo.findAll().stream()
                    .filter(e -> e.getStatus() == OutboxEvent.Status.PENDING).count();
            assertThat(pending).isZero();
        });

        // Verify at least one UserRegistered message published
        Properties cp = new Properties();
        cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        cp.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(cp)) {
            c.subscribe(List.of("user-events"));
            long deadline = System.currentTimeMillis() + 15_000;
            boolean sawRegistered = false;
            while (System.currentTimeMillis() < deadline && !sawRegistered) {
                for (ConsumerRecord<String, String> rec : c.poll(Duration.ofSeconds(2))) {
                    JsonNode env = mapper.readTree(rec.value());
                    if ("UserRegistered".equals(env.get("eventType").asText())) {
                        sawRegistered = true;
                        assertThat(env.get("source").asText()).isEqualTo("auth-service");
                        assertThat(env.get("data").get("email").asText()).isEqualTo(email.toLowerCase());
                    }
                }
            }
            assertThat(sawRegistered).as("UserRegistered event published").isTrue();
        }
    }
}
