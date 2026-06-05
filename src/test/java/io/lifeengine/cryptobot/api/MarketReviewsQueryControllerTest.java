package io.lifeengine.cryptobot.api;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.domain.MarketReviewRun;
import io.lifeengine.cryptobot.domain.MarketReviewRunStatus;
import io.lifeengine.cryptobot.domain.MarketReviewVerdict;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketReviewRunRepository;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class MarketReviewsQueryControllerTest {

    @Autowired private WebTestClient webTestClient;
    @Autowired private MarketReviewRunRepository repo;

    @Test
    void latest_returnsOneRowPerSymbol_inRequestedOrder() {
        UUID btcId = UUID.randomUUID();
        UUID solId = UUID.randomUUID();
        MarketReviewRun btc = run(btcId, "BTCUSDT", MarketReviewRunStatus.SUCCEEDED, "BULLISH",
                "BTC OK", Instant.parse("2026-05-20T13:10:00Z"));
        MarketReviewRun sol = run(solId, "SOLUSDT", MarketReviewRunStatus.SUCCEEDED, "NEUTRAL",
                "SOL OK", Instant.parse("2026-05-20T13:11:00Z"));

        Mockito.when(repo.findLatestBySymbol("BTCUSDT")).thenReturn(Mono.just(btc));
        Mockito.when(repo.findLatestBySymbol("SOLUSDT")).thenReturn(Mono.just(sol));

        String token = signJwt(List.of("RUNTIME_OPERATOR"));

        webTestClient
                .get()
                .uri("/api/cryptobot/market-reviews/latest?symbols=BTCUSDT,SOLUSDT")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].symbol").isEqualTo("BTCUSDT")
                .jsonPath("$[0].verdict").isEqualTo("BULLISH")
                .jsonPath("$[0].status").isEqualTo("SUCCEEDED")
                .jsonPath("$[0].summaryPreview").isEqualTo("BTC OK")
                .jsonPath("$[1].symbol").isEqualTo("SOLUSDT")
                .jsonPath("$[1].verdict").isEqualTo("NEUTRAL");
    }

    @Test
    void latest_skipsSymbolsWithNoHistory() {
        Mockito.when(repo.findLatestBySymbol("BTCUSDT")).thenReturn(Mono.empty());
        Mockito.when(repo.findLatestBySymbol("SOLUSDT"))
                .thenReturn(
                        Mono.just(
                                run(
                                        UUID.randomUUID(),
                                        "SOLUSDT",
                                        MarketReviewRunStatus.RUNNING,
                                        null,
                                        null,
                                        Instant.parse("2026-05-20T13:00:00Z"))));

        String token = signJwt(List.of("RUNTIME_OPERATOR"));

        webTestClient
                .get()
                .uri("/api/cryptobot/market-reviews/latest?symbols=BTCUSDT,SOLUSDT")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].symbol").isEqualTo("SOLUSDT");
    }

    @Test
    void history_returnsListOrderedDesc() {
        MarketReviewRun newer = run(UUID.randomUUID(), "BTCUSDT", MarketReviewRunStatus.SUCCEEDED,
                "BULLISH", "newer", Instant.parse("2026-05-20T13:30:00Z"));
        MarketReviewRun older = run(UUID.randomUUID(), "BTCUSDT", MarketReviewRunStatus.SUCCEEDED,
                "NEUTRAL", "older", Instant.parse("2026-05-20T13:00:00Z"));

        Mockito.when(repo.findRecentBySymbol(ArgumentMatchers.eq("BTCUSDT"), ArgumentMatchers.eq(5)))
                .thenReturn(Flux.just(newer, older));

        String token = signJwt(List.of("RUNTIME_OPERATOR"));

        webTestClient
                .get()
                .uri("/api/cryptobot/market-reviews?symbol=BTCUSDT&limit=5")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].summaryPreview").isEqualTo("newer")
                .jsonPath("$[1].summaryPreview").isEqualTo("older");
    }

    @Test
    void latest_returns401_whenNoToken() {
        webTestClient
                .get()
                .uri("/api/cryptobot/market-reviews/latest?symbols=BTCUSDT,SOLUSDT")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    private static MarketReviewRun run(
            UUID id, String symbol, MarketReviewRunStatus status, String verdict, String summary, Instant startedAt) {
        MarketReviewVerdict v = verdict == null ? null : MarketReviewVerdict.valueOf(verdict);
        return new MarketReviewRun(
                id,
                symbol,
                UUID.randomUUID(),
                "crypto.market-review.v1",
                status,
                "test@local",
                startedAt,
                status.isTerminal() ? startedAt.plusSeconds(20) : null,
                v,
                summary,
                null,
                null,
                Map.of("llmCallCount", 3, "toolCallCount", 2),
                startedAt,
                startedAt);
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
