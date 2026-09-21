package io.lifeengine.cryptobot.adapters.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** KAN-572: Coinbase spot — one request per product, dated by fetch time, a failure is an absent observation. */
class CoinbaseSpotPriceSourceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final Map<String, String> ASKED = Map.of(TokenRegistry.NATIVE_SOL_MINT, "SOL", TokenRegistry.USDC_MINT, "USDC");
    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private MarketDataProperties props(boolean enabled) {
        String base = "http://localhost:" + server.getPort();
        return new MarketDataProperties(base, Duration.ofSeconds(2), Map.of(), Map.of(), false,
                new MarketDataProperties.Pyth(false, base, Map.of()), new MarketDataProperties.CoinGecko(false, base, Map.of()),
                new MarketDataProperties.Coinbase(enabled, base, Map.of("SOL", "SOL-USD", "USDC", "USDC-USD")), null);
    }

    @Test
    void oneSpotRequestPerProductDatedByFetchTime() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.equals("/v2/prices/SOL-USD/spot")) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"data\":{\"amount\":\"111.47\",\"base\":\"SOL\",\"currency\":\"USD\"}}");
                }
                if (path.equals("/v2/prices/USDC-USD/spot")) {
                    return new MockResponse().setResponseCode(503); // one product down: only that observation is missing
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        CoinbaseSpotPriceSource cb = new CoinbaseSpotPriceSource(WebClient.builder(), props(true), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(cb.id()).isEqualTo("coinbase-spot");
        StepVerifier.create(cb.observe(ASKED))
                .assertNext(obs -> {
                    assertThat(obs).hasSize(1);
                    assertThat(obs.get(0).asset()).isEqualTo("SOL");
                    assertThat(obs.get(0).mint()).isEqualTo(TokenRegistry.NATIVE_SOL_MINT);
                    assertThat(obs.get(0).priceUsd()).isEqualByComparingTo("111.47");
                    assertThat(obs.get(0).observedAt()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void disabledOrUnknownSymbolObservesNothing() {
        CoinbaseSpotPriceSource off = new CoinbaseSpotPriceSource(WebClient.builder(), props(false), new ObjectMapper());
        StepVerifier.create(off.observe(ASKED)).assertNext(obs -> assertThat(obs).isEmpty()).verifyComplete();
        CoinbaseSpotPriceSource on = new CoinbaseSpotPriceSource(WebClient.builder(), props(true), new ObjectMapper());
        StepVerifier.create(on.observe(Map.of("mint-x", "XYZ"))).assertNext(obs -> assertThat(obs).isEmpty()).verifyComplete();
        assertThat(server.getRequestCount()).isZero();
    }
}
