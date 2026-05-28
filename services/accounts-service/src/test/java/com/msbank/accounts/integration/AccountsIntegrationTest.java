package com.msbank.accounts.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AccountsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("accounts").withUsername("msbank").withPassword("msbank_dev_only");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @MockBean
    JwtDecoder jwtDecoder;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @Test
    void openDepositAndProject_endToEnd() throws Exception {
        UUID customerId = UUID.randomUUID();
        stubJwtFor(customerId);

        // 1. Open account
        String openBody = """
                {"accountType":"CHECKING","currency":"USD","nickname":"primary"}
                """;
        MvcResult openResult = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openBody))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode openJson = mapper.readTree(openResult.getResponse().getContentAsString());
        UUID accountId = UUID.fromString(openJson.get("id").asText());

        // 2. Wait for projection
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/accounts/" + accountId).header("Authorization", "Bearer fake"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("ACTIVE")));

        // 3. Deposit
        String depositBody = mapper.writeValueAsString(Map.of(
                "idempotencyKey", UUID.randomUUID().toString(),
                "money", Map.of("amount", 12345L, "currency", "USD"),
                "description", "test"));
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody))
                .andExpect(status().isAccepted());

        // 4. Projection eventually consistent
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/accounts/" + accountId).header("Authorization", "Bearer fake"))
                        .andExpect(jsonPath("$.balance").value(12345L)));

        // 5. /accounts list and /events endpoint
        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(accountId.toString()));

        MvcResult eventsRes = mockMvc.perform(get("/api/v1/accounts/" + accountId + "/events")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode events = mapper.readTree(eventsRes.getResponse().getContentAsString());
        assertThat(events.isArray()).isTrue();
        assertThat(events.size()).isGreaterThanOrEqualTo(2);
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("AccountOpened");

        // 6. Verify outbox -> Kafka. Wait for poller, then consume.
        List<String> messages = consume(Duration.ofSeconds(15), 2);
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("AccountOpened"));
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("Deposited"));
    }

    private void stubJwtFor(UUID customerId) {
        Jwt jwt = Jwt.withTokenValue("fake")
                .header("alg", "RS256")
                .subject(customerId.toString())
                .audience(List.of("msbank"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        org.mockito.Mockito.when(jwtDecoder.decode(org.mockito.ArgumentMatchers.anyString())).thenReturn(jwt);
    }

    private List<String> consume(Duration timeout, int minMessages) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        java.util.List<String> collected = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(Collections.singletonList("account-events"));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline && collected.size() < minMessages) {
                var recs = c.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : recs) collected.add(r.value());
            }
        }
        return collected;
    }
}
