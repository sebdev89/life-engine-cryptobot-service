package io.lifeengine.cryptobot.api.backtest;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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

/**
 * {@code POST /api/cryptobot/backtests} end to end over a fake Coinbase (KAN-1216): SOL 100 → 120 → 140 → 160
 * hourly, so holding SOL ends at 1600, holding USDC at 1000, and a 50/50 band rebalance in between.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class BacktestControllerTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static MockWebServer coinbase;

    @Autowired private WebTestClient webTestClient;

    @BeforeAll
    static void startMock() throws Exception {
        coinbase = new MockWebServer();
        coinbase.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/products/SOL-USD/candles")) {
                    long t = FROM.getEpochSecond();
                    String body = "[[" + (t + 10800) + ",1,2,3,160,9],[" + (t + 7200) + ",1,2,3,140,9],["
                            + (t + 3600) + ",1,2,3,120,9],[" + t + ",1,2,3,100,9]]";
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        coinbase.start();
    }

    @AfterAll
    static void stopMock() throws Exception {
        coinbase.shutdown();
    }

    @DynamicPropertySource
    static void historyBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("cryptobot.backtest.history-base-url", () -> "http://localhost:" + coinbase.getPort());
    }

    private WebTestClient.ResponseSpec post(Map<String, Object> body, String token) {
        WebTestClient.RequestBodySpec spec = webTestClient.post().uri("/api/cryptobot/backtests").contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return spec.bodyValue(body).exchange();
    }

    @Test
    void bandRebalanceIsReportedNextToBothBenchmarks() {
        post(Map.of("strategy", "band-rebalance", "from", FROM.toString(), "to", FROM.plusSeconds(4 * 3600).toString(),
                        "targetSolWeight", 0.5, "bandBps", 500),
                signJwt(List.of("RUNTIME_OPERATOR")))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("coinbase-exchange-candles")
                .jsonPath("$.product").isEqualTo("SOL-USD")
                .jsonPath("$.missingBars").isEqualTo(0)
                .jsonPath("$.report.strategy.strategyId").isEqualTo("band-rebalance")
                .jsonPath("$.report.strategy.bars").isEqualTo(4)
                .jsonPath("$.report.holdSol.finalUsd").isEqualTo(1600.0)
                .jsonPath("$.report.holdUsdc.finalUsd").isEqualTo(1000.0)
                .jsonPath("$.report.beatsHoldSol").isEqualTo(false)
                .jsonPath("$.report.beatsHoldUsdc").isEqualTo(true)
                .jsonPath("$.report.beatsDoingNothing").isEqualTo(false);
    }

    @Test
    void anUnknownStrategyIs400() {
        post(Map.of("strategy", "martingale", "from", FROM.toString(), "to", FROM.plusSeconds(4 * 3600).toString()),
                signJwt(List.of("RUNTIME_OPERATOR")))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_BACKTEST");
    }

    @Test
    void withoutATokenIs401() {
        post(Map.of("strategy", "hold-sol", "from", FROM.toString(), "to", FROM.plusSeconds(4 * 3600).toString()), null)
                .expectStatus().isUnauthorized();
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
