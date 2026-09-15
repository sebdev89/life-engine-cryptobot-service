package io.lifeengine.cryptobot.api.quotes;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code GET /api/cryptobot/quotes/{asset}} end to end: real security filter, real
 * {@link io.lifeengine.cryptobot.application.quotes.CachedArsQuotesService}, the three real adapters —
 * all pointed at one local MockWebServer that answers each exchange's path with its recorded fixture.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class QuotesControllerTest {

    private static final String TEST_SECRET = "test-jwt-secret-at-least-32-bytes-long!!";
    private static MockWebServer exchanges;

    @Autowired private WebTestClient web;

    @BeforeAll
    static void startExchanges() throws Exception {
        exchanges = new MockWebServer();
        exchanges.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/v3/ticker/")) {
                    return json(fixture("bitso-ticker.json"));
                }
                if (path.startsWith("/api/v3/rates/")) {
                    return json(fixture("ripio-rates.json"));
                }
                if (path.startsWith("/api/market/tickers/")) {
                    return json(fixture("buenbit-tickers.json"));
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        exchanges.start();
    }

    @AfterAll
    static void stopExchanges() throws Exception {
        exchanges.shutdown();
    }

    @DynamicPropertySource
    static void pointAdaptersAtTheMock(DynamicPropertyRegistry registry) {
        for (String id : List.of("bitso", "ripio", "buenbit")) {
            registry.add("cryptobot.quotes.exchanges." + id + ".base-url", () -> "http://localhost:" + exchanges.getPort());
        }
        // One hand-maintained fee so the CONFIGURED path is visible end to end.
        registry.add("cryptobot.quotes.withdrawal-fees.ripio.USDT.TRON", () -> "1");
    }

    @Test
    void quotes_returns401_whenNoToken() {
        web.get().uri("/api/cryptobot/quotes/BTC").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void quotes_returns400_onBadInput() {
        web.get().uri("/api/cryptobot/quotes/BTC?ars=-5").header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_AMOUNT");
        web.get().uri("/api/cryptobot/quotes/BTC?side=HOLD").header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_SIDE");
        web.get().uri("/api/cryptobot/quotes/BTC?network=not%20a%20network").header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_NETWORK");
        web.get().uri("/api/cryptobot/quotes/B").header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_ASSET");
        web.get().uri("/api/cryptobot/quotes/BTC?side=SELL&ars=100").header(HttpHeaders.AUTHORIZATION, bearer()).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_AMOUNT");
    }

    @Test
    void buyBtcWithPesos_ranksTheThreeExchangesByBtcReceived() {
        web.get().uri("/api/cryptobot/quotes/btc?ars=1000000")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.asset").isEqualTo("BTC")
                .jsonPath("$.side").isEqualTo("BUY")
                .jsonPath("$.amount").isEqualTo(1000000)
                .jsonPath("$.amountUnit").isEqualTo("ARS")
                .jsonPath("$.exchanges").isEqualTo(List.of("bitso", "ripio", "buenbit"))
                .jsonPath("$.unavailable").isEmpty()
                .jsonPath("$.ranking.length()").isEqualTo(3)
                // asks: buenbit 149.5M < bitso 150M < ripio 151.2M
                .jsonPath("$.ranking[0].exchange").isEqualTo("buenbit")
                .jsonPath("$.ranking[0].rank").isEqualTo(1)
                .jsonPath("$.ranking[0].receives").isEqualTo(0.00668896)
                .jsonPath("$.ranking[0].receivesUnit").isEqualTo("BTC")
                .jsonPath("$.ranking[0].stale").isEqualTo(false)
                .jsonPath("$.ranking[1].exchange").isEqualTo("bitso")
                .jsonPath("$.ranking[2].exchange").isEqualTo("ripio")
                .jsonPath("$.ranking[2].ask").isEqualTo(151200000.00)
                .jsonPath("$.ranking[2].bid").isEqualTo(147300000.00);
    }

    @Test
    void buyUsdtOverTron_appliesConfiguredFeeAndFlagsUnknownOnes() {
        web.get().uri("/api/cryptobot/quotes/USDT?ars=100000&network=tron")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.network").isEqualTo("TRON")
                // ripio 100000/1495 − 1 = 65.8896 · buenbit 66.6666 (fee unknown, not applied) · bitso 66.2251 (unknown)
                .jsonPath("$.ranking[0].exchange").isEqualTo("buenbit")
                .jsonPath("$.ranking[0].feeKnown").isEqualTo(false)
                .jsonPath("$.ranking[0].fee").doesNotExist()
                .jsonPath("$.ranking[2].exchange").isEqualTo("ripio")
                .jsonPath("$.ranking[2].receives").isEqualTo(65.8896321)
                .jsonPath("$.ranking[2].fee.network").isEqualTo("TRON")
                .jsonPath("$.ranking[2].fee.amount").isEqualTo(1)
                .jsonPath("$.ranking[2].fee.source").isEqualTo("CONFIGURED")
                .jsonPath("$.ranking[2].feeKnown").isEqualTo(true);
    }

    @Test
    void assetNotListedEverywhere_isReportedPerExchange() {
        web.get().uri("/api/cryptobot/quotes/DAI?side=SELL&amount=100")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.side").isEqualTo("SELL")
                .jsonPath("$.amountUnit").isEqualTo("DAI")
                .jsonPath("$.ranking.length()").isEqualTo(1)
                .jsonPath("$.ranking[0].exchange").isEqualTo("buenbit")
                .jsonPath("$.ranking[0].receives").isEqualTo(148100.00)
                .jsonPath("$.ranking[0].receivesUnit").isEqualTo("ARS")
                .jsonPath("$.unavailable.length()").isEqualTo(2)
                .jsonPath("$.unavailable[0].exchange").isEqualTo("bitso")
                .jsonPath("$.unavailable[0].reason").isEqualTo("NOT_LISTED")
                .jsonPath("$.unavailable[1].exchange").isEqualTo("ripio");
    }

    @Test
    void boardIsCached_secondCallDoesNotHitTheExchanges() {
        int before = exchanges.getRequestCount();
        web.get().uri("/api/cryptobot/quotes/BTC").header(HttpHeaders.AUTHORIZATION, bearer()).exchange().expectStatus().isOk();
        int afterFirst = exchanges.getRequestCount();
        web.get().uri("/api/cryptobot/quotes/USDT").header(HttpHeaders.AUTHORIZATION, bearer()).exchange().expectStatus().isOk();
        assertThat(afterFirst - before).isBetween(0, 3); // 0 if another test already warmed the cache
        assertThat(exchanges.getRequestCount()).isEqualTo(afterFirst);
    }

    // ---- helpers ----

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String fixture(String name) {
        try (InputStream in = QuotesControllerTest.class.getResourceAsStream("/quotes/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String bearer() {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return "Bearer " + Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "admin@life-engine.local")
                .claim("role", "ADMIN")
                .claim("authorities", List.of("ROLE_ADMIN", "ROLE_USER"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(key)
                .compact();
    }
}
