package io.lifeengine.cryptobot.adapters.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class JupiterPriceClientTest {

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
        return new JupiterPriceClient(WebClient.builder(), props, new TokenRegistry(props), new ObjectMapper());
    }

    @Test
    void parsesV3ShapeAndFillsMissingWithFallback() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"So11111111111111111111111111111111111111112\":{\"usdPrice\":102.5,\"priceChange24h\":1.2}}"));
        StepVerifier.create(client(true).prices(Set.of(TokenRegistry.NATIVE_SOL_MINT, TokenRegistry.USDC_MINT)))
                .assertNext(q -> {
                    assertThat(q.get(TokenRegistry.NATIVE_SOL_MINT).priceUsd()).isEqualByComparingTo("102.5");
                    assertThat(q.get(TokenRegistry.NATIVE_SOL_MINT).source()).isEqualTo(JupiterPriceClient.SOURCE_JUPITER);
                    assertThat(q.get(TokenRegistry.USDC_MINT).source()).isEqualTo(JupiterPriceClient.SOURCE_FALLBACK);
                })
                .verifyComplete();
    }

    @Test
    void serverFailureDegradesToLabelledFallback() {
        server.enqueue(new MockResponse().setResponseCode(503));
        StepVerifier.create(client(true).prices(Set.of(TokenRegistry.NATIVE_SOL_MINT)))
                .assertNext(q -> {
                    assertThat(q.get(TokenRegistry.NATIVE_SOL_MINT).priceUsd()).isEqualByComparingTo("100");
                    assertThat(q.get(TokenRegistry.NATIVE_SOL_MINT).source()).isEqualTo(JupiterPriceClient.SOURCE_FALLBACK);
                })
                .verifyComplete();
    }

    @Test
    void disabledNeverCallsTheNetwork() {
        int before = server.getRequestCount();
        StepVerifier.create(client(false).prices(Set.of(TokenRegistry.NATIVE_SOL_MINT)))
                .assertNext(q -> assertThat(q.get(TokenRegistry.NATIVE_SOL_MINT).source()).isEqualTo(JupiterPriceClient.SOURCE_FALLBACK))
                .verifyComplete();
        assertThat(server.getRequestCount()).isEqualTo(before);
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
