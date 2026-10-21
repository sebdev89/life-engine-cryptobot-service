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
    private static final Instant STOCK_DAY = Instant.parse("2026-06-15T13:30:00Z");
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
                // KAN-1226: Yahoo chart, four daily sessions. SPCX 100 → 110 (+10 %), QQQ 100 → 103 (+3 %)
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/v8/finance/chart/SPCX") || path.startsWith("/v8/finance/chart/QQQ")) {
                    long d = STOCK_DAY.getEpochSecond();
                    String closes = path.contains("SPCX") ? "100,80,90,110" : "100,101,102,103";
                    String body = "{\"chart\":{\"result\":[{\"timestamp\":[" + d + "," + (d + 86400) + "," + (d + 2 * 86400) + ","
                            + (d + 3 * 86400) + "],\"indicators\":{\"quote\":[{\"close\":[" + closes + "]}]}}],\"error\":null}}";
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
        registry.add("cryptobot.backtest.stocks.base-url", () -> "http://localhost:" + coinbase.getPort());
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
    void aStockIsComparedWithCashAndWithTheIndexOverTheSameSessions() {
        post(Map.of("market", "stock", "symbol", "spcx", "strategy", "hold-sol",
                        "from", STOCK_DAY.minusSeconds(3600).toString(), "to", STOCK_DAY.plusSeconds(4 * 86400).toString()),
                signJwt(List.of("RUNTIME_OPERATOR")))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.market").isEqualTo("stock")
                .jsonPath("$.source").isEqualTo("yahoo-chart")
                .jsonPath("$.product").isEqualTo("SPCX")
                .jsonPath("$.granularitySeconds").isEqualTo(86400)
                .jsonPath("$.missingBars").doesNotExist()
                .jsonPath("$.report.strategy.bars").isEqualTo(4)
                // holding the stock: 100 → 110; cash: flat; the index (QQQ by default): 100 → 103
                .jsonPath("$.report.holdSol.finalUsd").isEqualTo(1100.0)
                .jsonPath("$.report.holdUsdc.finalUsd").isEqualTo(1000.0)
                .jsonPath("$.report.fees.feeBps").isEqualTo(5)
                .jsonPath("$.benchmark").isEqualTo("QQQ")
                .jsonPath("$.benchmarkResult.finalUsd").isEqualTo(1030.0)
                .jsonPath("$.beatsBenchmark").isEqualTo(true);
    }

    @Test
    void aStockWithoutASymbolIs400() {
        post(Map.of("market", "stock", "strategy", "hold-sol", "from", STOCK_DAY.toString(), "to", STOCK_DAY.plusSeconds(86400).toString()),
                signJwt(List.of("RUNTIME_OPERATOR")))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").isEqualTo("market=stock needs a symbol (e.g. SPCX)");
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
