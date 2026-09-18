package io.lifeengine.cryptobot.adapters.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class JupiterPriceClientTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static MockWebServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterAll
    static void stop() throws Exception {
        server.shutdown();
    }

    private static JupiterPriceClient client(boolean enabled) {
        MarketDataProperties props = new MarketDataProperties("http://localhost:" + server.getPort(), Duration.ofSeconds(2), Map.of(),
                Map.of("SOL", new BigDecimal("100"), "USDC", BigDecimal.ONE), enabled);
        return new JupiterPriceClient(WebClient.builder(), props, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final Map<String, String> ASKED = Map.of(TokenRegistry.NATIVE_SOL_MINT, "SOL", TokenRegistry.USDC_MINT, "USDC");

    @Test
    void parsesV3ShapeAndObservesOnlyWhatJupiterPriced() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"So11111111111111111111111111111111111111112\":{\"usdPrice\":102.5,\"priceChange24h\":1.2}}"));
        StepVerifier.create(client(true).observe(ASKED))
                .assertNext(obs -> {
                    // KAN-439: no static fallback here — USDC is simply not observed, and the oracle counts that against the quorum
                    assertThat(obs).hasSize(1);
                    assertThat(obs.get(0).source()).isEqualTo(JupiterPriceClient.SOURCE_JUPITER);
                    assertThat(obs.get(0).asset()).isEqualTo("SOL");
                    assertThat(obs.get(0).mint()).isEqualTo(TokenRegistry.NATIVE_SOL_MINT);
                    assertThat(obs.get(0).priceUsd()).isEqualByComparingTo("102.5");
                    assertThat(obs.get(0).observedAt()).isEqualTo(NOW); // Jupiter has no timestamp: dated at fetch
                })
                .verifyComplete();
    }

    @Test
    void parsesLegacyV2Shape() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v\":{\"price\":\"0.9998\"}}}"));
        StepVerifier.create(client(true).observe(ASKED))
                .assertNext(obs -> {
                    assertThat(obs).hasSize(1);
                    assertThat(obs.get(0).asset()).isEqualTo("USDC");
                    assertThat(obs.get(0).priceUsd()).isEqualByComparingTo("0.9998");
                })
                .verifyComplete();
    }

    @Test
    void serverFailureIsAnAbsentObservationNeverAnError() {
        server.enqueue(new MockResponse().setResponseCode(503));
        StepVerifier.create(client(true).observe(ASKED)).assertNext(obs -> assertThat(obs).isEmpty()).verifyComplete();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("not json"));
        StepVerifier.create(client(true).observe(ASKED)).assertNext(obs -> assertThat(obs).isEmpty()).verifyComplete();
    }

    @Test
    void disabledNeverCallsTheNetwork() {
        int before = server.getRequestCount();
        StepVerifier.create(client(false).observe(ASKED)).assertNext(obs -> assertThat(obs).isEmpty()).verifyComplete();
        assertThat(server.getRequestCount()).isEqualTo(before);
        assertThat(client(false).enabled()).isFalse();
        assertThat(client(true).id()).isEqualTo("jupiter-price-v3");
    }

    @Test
    void registryAliasesDevnetMintsToMainnetPrices() {
        MarketDataProperties props = new MarketDataProperties(null, null, Map.of("DevMint1111111111111111111111111111111111111", TokenRegistry.NATIVE_SOL_MINT), Map.of(), false);
        TokenRegistry registry = new TokenRegistry(props);
        assertThat(registry.symbolOf("DevMint1111111111111111111111111111111111111")).isEqualTo("SOL");
        assertThat(registry.priceMintOf("DevMint1111111111111111111111111111111111111")).contains(TokenRegistry.NATIVE_SOL_MINT);
        assertThat(registry.symbolOf("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")).isEqualTo("USDC");
        assertThat(registry.isStable("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")).isTrue();
        assertThat(registry.symbolOf("Unknown111111111111111111111111111111111111")).startsWith("UNKNOWN-");
        assertThat(registry.mintOfSymbol("usdc")).contains(TokenRegistry.USDC_MINT);
    }
}
