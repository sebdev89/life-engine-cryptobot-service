package io.lifeengine.cryptobot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class MonitoringControllerTest {

    private static MockWebServer runtimeMock;

    @Autowired private WebTestClient webTestClient;
    @Autowired private ObjectMapper objectMapper;

    @BeforeAll
    static void startMock() throws Exception {
        runtimeMock = new MockWebServer();
        runtimeMock.start();
    }

    @AfterAll
    static void stopMock() throws Exception {
        runtimeMock.shutdown();
    }

    @DynamicPropertySource
    static void runtimeBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("cryptobot.runtime.base-url", () -> "http://localhost:" + runtimeMock.getPort());
        registry.add("cryptobot.monitoring.symbols", () -> "BTCUSDT,SOLUSDT");
    }

    @Test
    void runOnce_triggersOneRuntimeRunPerConfiguredSymbol() throws Exception {
        Set<String> startedSymbols = ConcurrentHashMap.newKeySet();
        runtimeMock.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        if ("POST".equals(request.getMethod())
                                && "/api/runtime/runs".equals(request.getPath())) {
                            String body = request.getBody().readUtf8();
                            if (body.contains("BTCUSDT")) startedSymbols.add("BTCUSDT");
                            if (body.contains("SOLUSDT")) startedSymbols.add("SOLUSDT");
                            try {
                                return new MockResponse()
                                        .setHeader("Content-Type", "application/json")
                                        .setBody(
                                                objectMapper.writeValueAsString(
                                                        Map.of(
                                                                "runId", UUID.randomUUID().toString(),
                                                                "workflowId", "crypto.market-review.v1",
                                                                "correlationId", "mon-corr",
                                                                "status", "RUNNING")));
                            } catch (Exception e) {
                                return new MockResponse().setResponseCode(500);
                            }
                        }
                        // Reconcile GET — just return a still-running snapshot so the loop doesn't
                        // crash. Body shape is tolerated by RuntimeRunDetail (ignoreUnknown).
                        try {
                            return new MockResponse()
                                    .setHeader("Content-Type", "application/json")
                                    .setBody(
                                            objectMapper.writeValueAsString(
                                                    Map.of(
                                                            "runId", UUID.randomUUID().toString(),
                                                            "workflowId", "crypto.market-review.v1",
                                                            "status", "RUNNING",
                                                            "agentStages", List.of(),
                                                            "llmCalls", List.of(),
                                                            "events", List.of())));
                        } catch (Exception e) {
                            return new MockResponse().setResponseCode(500);
                        }
                    }
                });

        String token = signJwt(List.of("RUNTIME_OPERATOR"));

        byte[] rawBody = webTestClient
                .post()
                .uri("/api/cryptobot/monitoring/run-once")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.configuredSymbols.length()").isEqualTo(2)
                .jsonPath("$.triggered.length()").isEqualTo(2)
                .returnResult()
                .getResponseBody();

        Assertions.assertThat(rawBody).isNotNull();
        MonitoringController.MonitoringRunOnceResponse parsed =
                objectMapper.readValue(rawBody, MonitoringController.MonitoringRunOnceResponse.class);
        Assertions.assertThat(parsed.triggered())
                .extracting(MonitoringController.TriggeredRun::symbol)
                .containsExactlyInAnyOrder("BTCUSDT", "SOLUSDT");
        Assertions.assertThat(parsed.triggered())
                .allSatisfy(t -> Assertions.assertThat(t.runtimeRunId()).isNotNull());

        Assertions.assertThat(startedSymbols).containsExactlyInAnyOrder("BTCUSDT", "SOLUSDT");
    }

    @Test
    void runOnce_returns401_whenNoToken() {
        webTestClient
                .post()
                .uri("/api/cryptobot/monitoring/run-once")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    private static String signJwt(List<String> authorities) {
        SecretKey key = Keys.hmacShaKeyFor(
                "test-jwt-secret-at-least-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "operator@test.local")
                .claim("authorities", authorities)
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plusSeconds(300)))
                .signWith(key)
                .compact();
    }
}
