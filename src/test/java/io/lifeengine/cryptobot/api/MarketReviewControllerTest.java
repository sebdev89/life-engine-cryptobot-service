package io.lifeengine.cryptobot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class MarketReviewControllerTest {

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
    }

    @Test
    void marketReview_returns401_whenNoToken() {
        webTestClient
                .post()
                .uri("/api/cryptobot/market-review")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"symbol\":\"BTCUSDT\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void marketReview_returns200_andLinksRuntimeRunId() throws Exception {
        UUID runtimeRunId = UUID.randomUUID();
        runtimeMock.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                objectMapper.writeValueAsString(
                                        java.util.Map.of(
                                                "runId", runtimeRunId.toString(),
                                                "workflowId", "crypto.market-review.v1",
                                                "correlationId", "cryptobot-mr-test",
                                                "status", "RUNNING"))));
        // Background reconciliation polls GET /api/runtime/runs/{runId} after a short delay.
        // Enqueue a couple of "still RUNNING" responses so we don't get MockWebServer NPEs
        // even if the controller test finishes before the poll fires.
        for (int i = 0; i < 4; i++) {
            runtimeMock.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    objectMapper.writeValueAsString(
                                            java.util.Map.of(
                                                    "runId", runtimeRunId.toString(),
                                                    "workflowId", "crypto.market-review.v1",
                                                    "status", "RUNNING",
                                                    "agentStages", java.util.List.of(),
                                                    "llmCalls", java.util.List.of(),
                                                    "events", java.util.List.of()))));
        }

        String token = signJwt(List.of("RUNTIME_OPERATOR"));

        webTestClient
                .post()
                .uri("/api/cryptobot/market-review")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"symbol\":\"BTCUSDT\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.symbol")
                .isEqualTo("BTCUSDT")
                .jsonPath("$.related.runtimeRunId")
                .isEqualTo(runtimeRunId.toString())
                .jsonPath("$.related.runtimeWorkflowId")
                .isEqualTo("crypto.market-review.v1")
                .jsonPath("$.related.ssePath")
                .isEqualTo("/api/runtime/runs/" + runtimeRunId + "/stream")
                .jsonPath("$.marketReviewRunId")
                .exists();

        RecordedRequest sent = runtimeMock.takeRequest();
        Assertions.assertThat(sent.getPath()).isEqualTo("/api/runtime/runs");
        Assertions.assertThat(sent.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + token);
        String body = sent.getBody().readUtf8();
        Assertions.assertThat(body).contains("\"workflowId\":\"crypto.market-review.v1\"");
        // input is a JSON-string field (the runtime expects an opaque string), so symbol+marketReviewId
        // appear with their quotes escaped inside the outer JSON.
        Assertions.assertThat(body).contains("\\\"symbol\\\":\\\"BTCUSDT\\\"");
        Assertions.assertThat(body).contains("\\\"marketReviewId\\\"");
    }

    @Test
    void marketReview_returns403_whenAuthorityMissing() {
        String token = signJwt(List.of("RUNTIME_VIEWER")); // missing RUNTIME_OPERATOR
        webTestClient
                .post()
                .uri("/api/cryptobot/market-review")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"symbol\":\"BTCUSDT\"}")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void marketReview_returns400_onInvalidSymbol() {
        String token = signJwt(List.of("RUNTIME_OPERATOR"));
        webTestClient
                .post()
                .uri("/api/cryptobot/market-review")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"symbol\":\"BTC/USDT\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INVALID_SYMBOL");
    }

    private static String signJwt(List<String> authorities) {
        SecretKey key = Keys.hmacShaKeyFor("test-jwt-secret-at-least-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
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
