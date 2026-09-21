package io.lifeengine.cryptobot.adapters.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** The two sources that make Jupiter a minority: Pyth (pull oracle, own publish time) and CoinGecko (CEX aggregate). */
class PythAndCoinGeckoSourcesTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final String SOL_FEED = "ef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d";
    private static final String USDC_FEED = "eaa020c61cc479712813461ce153894a96a6c00b21ed0cfc2798d1f9a9e9c94a";
    private static final Map<String, String> ASKED = Map.of(TokenRegistry.NATIVE_SOL_MINT, "SOL", TokenRegistry.USDC_MINT, "USDC");
    // One server per test: a recorded request a test does not take must not be read by the next one.
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

    private MarketDataProperties props(boolean pythEnabled, boolean geckoEnabled) {
        String base = "http://localhost:" + server.getPort();
        return new MarketDataProperties(base, Duration.ofSeconds(2), Map.of(), Map.of(), false,
                new MarketDataProperties.Pyth(pythEnabled, base, Map.of("SOL", "0x" + SOL_FEED, "USDC", USDC_FEED)),
                new MarketDataProperties.CoinGecko(geckoEnabled, base, Map.of("SOL", "solana", "USDC", "usd-coin")),
                null);
    }

    @Test
    void pythParsesMantissaExponentAndPublishTime() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"binary\":{\"encoding\":\"hex\",\"data\":[\"00\"]},\"parsed\":["
                + "{\"id\":\"" + SOL_FEED + "\",\"price\":{\"price\":\"18012345678\",\"conf\":\"9000000\",\"expo\":-8,\"publish_time\":1789732795},\"ema_price\":{}},"
                + "{\"id\":\"" + USDC_FEED + "\",\"price\":{\"price\":\"99998000\",\"conf\":\"1000\",\"expo\":-8,\"publish_time\":1789732796},\"ema_price\":{}}]}"));
        PythHermesPriceSource pyth = new PythHermesPriceSource(WebClient.builder(), props(true, true), new ObjectMapper());
        StepVerifier.create(pyth.observe(ASKED))
                .assertNext(obs -> {
                    assertThat(obs).hasSize(2);
                    var sol = obs.stream().filter(o -> o.asset().equals("SOL")).findFirst().orElseThrow();
                    assertThat(sol.source()).isEqualTo(PythHermesPriceSource.SOURCE_PYTH);
                    assertThat(sol.mint()).isEqualTo(TokenRegistry.NATIVE_SOL_MINT);
                    assertThat(sol.priceUsd()).isEqualByComparingTo("180.12345678");
                    assertThat(sol.observedAt()).isEqualTo(Instant.ofEpochSecond(1789732795)); // the network's time, not our fetch
                    var usdc = obs.stream().filter(o -> o.asset().equals("USDC")).findFirst().orElseThrow();
                    assertThat(usdc.priceUsd()).isEqualByComparingTo("0.99998");
                })
                .verifyComplete();
        RecordedRequest req = server.takeRequest();
        String path = req.getPath().replace("%5B", "[").replace("%5D", "]");
        assertThat(path).startsWith("/v2/updates/price/latest?parsed=true").contains("ids[]=" + SOL_FEED).contains("ids[]=" + USDC_FEED);
        assertThat(path).doesNotContain("0x"); // the 0x prefix is stripped before asking
    }

    @Test
    void pythFailureOrUnknownFeedIsAnAbsentObservation() {
        server.enqueue(new MockResponse().setResponseCode(404));
        PythHermesPriceSource pyth = new PythHermesPriceSource(WebClient.builder(), props(true, true), new ObjectMapper());
        StepVerifier.create(pyth.observe(ASKED)).assertNext(obs -> assertThat(obs).isEmpty()).verifyComplete();
        // a symbol without a feed id is never asked
        int before = server.getRequestCount();
        StepVerifier.create(pyth.observe(Map.of("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", "JUP"))).assertNext(obs -> assertThat(obs).isEmpty()).verifyComplete();
        assertThat(server.getRequestCount()).isEqualTo(before);
        assertThat(new PythHermesPriceSource(WebClient.builder(), props(false, true), new ObjectMapper()).enabled()).isFalse();
    }

    @Test
    void coinGeckoParsesUsdAndLastUpdatedAt() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"solana\":{\"usd\":180.31,\"last_updated_at\":1789732700},\"usd-coin\":{\"usd\":0.999901}}"));
        CoinGeckoPriceSource gecko = new CoinGeckoPriceSource(WebClient.builder(), props(true, true), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        StepVerifier.create(gecko.observe(ASKED))
                .assertNext(obs -> {
                    assertThat(obs).hasSize(2);
                    var sol = obs.stream().filter(o -> o.asset().equals("SOL")).findFirst().orElseThrow();
                    assertThat(sol.source()).isEqualTo(CoinGeckoPriceSource.SOURCE_COINGECKO);
                    assertThat(sol.priceUsd()).isEqualByComparingTo("180.31");
                    assertThat(sol.observedAt()).isEqualTo(Instant.ofEpochSecond(1789732700)); // CoinGecko's own lag is visible
                    var usdc = obs.stream().filter(o -> o.asset().equals("USDC")).findFirst().orElseThrow();
                    assertThat(usdc.observedAt()).isEqualTo(NOW); // no last_updated_at ⇒ fetch time
                })
                .verifyComplete();
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).startsWith("/api/v3/simple/price?ids=").contains("solana").contains("usd-coin").contains("vs_currencies=usd");
    }

    @Test
    void coinGeckoFailureIsAnAbsentObservation() {
        server.enqueue(new MockResponse().setResponseCode(429));
        CoinGeckoPriceSource gecko = new CoinGeckoPriceSource(WebClient.builder(), props(true, true), new ObjectMapper());
        StepVerifier.create(gecko.observe(ASKED)).assertNext(obs -> assertThat(obs).isEmpty()).verifyComplete();
        assertThat(new CoinGeckoPriceSource(WebClient.builder(), props(true, false), new ObjectMapper()).enabled()).isFalse();
    }
}
