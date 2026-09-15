package io.lifeengine.cryptobot.infrastructure.solana;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** Runs against the real responses recorded on 2026-09-14 in {@code src/test/resources/solana/}. */
class SolanaPublicClientTest {

    static final String POOL = SolanaMarketProperties.DEFAULT_SOL_USDC_POOL;
    static final String SOL_MINT = "So11111111111111111111111111111111111111112";

    private static MockWebServer server;
    private static SolanaPublicClient client;

    @BeforeAll
    static void start() throws Exception {
        server = new MockWebServer();
        server.start();
        String base = "http://localhost:" + server.getPort();
        client = new SolanaPublicClient(WebClient.builder(), new SolanaMarketProperties(base, base, Duration.ofSeconds(2), Map.of(), Map.of()));
    }

    @AfterAll
    static void stop() throws Exception {
        server.shutdown();
    }

    static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/solana/" + name), StandardCharsets.UTF_8);
    }

    @Test
    void poolStatsFromRecordedGeckoTerminalResponse() throws Exception {
        server.enqueue(json(fixture("geckoterminal-pool.json")));
        StepVerifier.create(client.poolStats(POOL))
                .assertNext(s -> {
                    assertThat(s.name()).isEqualTo("SOL / USDC");
                    assertThat(s.baseSymbol()).isEqualTo("SOL");
                    assertThat(s.quoteSymbol()).isEqualTo("USDC");
                    assertThat(s.priceUsd()).isGreaterThan(java.math.BigDecimal.valueOf(50)).isLessThan(java.math.BigDecimal.valueOf(1000));
                    assertThat(s.change24hPct()).isNotNull();
                    assertThat(s.volume24hUsd()).isPositive();
                    assertThat(s.trades24h()).isPositive();
                })
                .verifyComplete();
        assertThat(lastPath()).isEqualTo("/api/v2/networks/solana/pools/" + POOL);
    }

    @Test
    void hourlyCandlesFromRecordedResponse() throws Exception {
        server.enqueue(json(fixture("geckoterminal-ohlcv-hour.json")));
        StepVerifier.create(client.ohlcvHour(POOL, 24))
                .assertNext(candles -> {
                    assertThat(candles).hasSize(24);
                    var c = candles.get(0);
                    assertThat(c.high()).isGreaterThanOrEqualTo(c.low());
                    assertThat(c.high()).isGreaterThanOrEqualTo(c.open()).isGreaterThanOrEqualTo(c.close());
                    assertThat(c.openTime()).isAfter(java.time.Instant.parse("2026-01-01T00:00:00Z"));
                    assertThat(c.volumeUsd()).isPositive();
                })
                .verifyComplete();
        assertThat(lastPath()).isEqualTo("/api/v2/networks/solana/pools/" + POOL + "/ohlcv/hour?limit=24");
    }

    @Test
    void jupiterPriceFromRecordedResponse() throws Exception {
        server.enqueue(json(fixture("jupiter-price-v3.json")));
        StepVerifier.create(client.jupiterPrice(SOL_MINT))
                .assertNext(p -> {
                    assertThat(p.mint()).isEqualTo(SOL_MINT);
                    assertThat(p.priceUsd()).isGreaterThan(java.math.BigDecimal.valueOf(50));
                    assertThat(p.change24hPct()).isNotNull();
                })
                .verifyComplete();
        assertThat(lastPath()).isEqualTo("/price/v3?ids=" + SOL_MINT);
    }

    @Test
    void upstreamFailuresBecomeEmptyLikeBinance() {
        server.enqueue(new MockResponse().setResponseCode(429));
        StepVerifier.create(client.poolStats(POOL)).verifyComplete();
        server.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(client.jupiterPrice(SOL_MINT)).verifyComplete();
        server.enqueue(json("{\"So11111111111111111111111111111111111111112\":null}"));
        StepVerifier.create(client.jupiterPrice(SOL_MINT)).verifyComplete();
    }

    /** Tests share one server and JUnit orders them freely: look at the most recent request only. */
    private static String lastPath() throws Exception {
        String last = null;
        okhttp3.mockwebserver.RecordedRequest r;
        while ((r = server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS)) != null) {
            last = r.getPath();
        }
        return last;
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
