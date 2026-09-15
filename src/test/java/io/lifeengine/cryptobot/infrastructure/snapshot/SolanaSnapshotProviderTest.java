package io.lifeengine.cryptobot.infrastructure.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.infrastructure.solana.SolanaMarketProperties;
import io.lifeengine.cryptobot.infrastructure.solana.SolanaPublicClient;
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

class SolanaSnapshotProviderTest {

    private static MockWebServer server;
    private static SolanaSnapshotProvider provider;

    @BeforeAll
    static void start() throws Exception {
        server = new MockWebServer();
        server.start();
        String base = "http://localhost:" + server.getPort();
        SolanaMarketProperties props = new SolanaMarketProperties(base, base, Duration.ofSeconds(2), Map.of(), Map.of());
        provider = new SolanaSnapshotProvider(new SolanaPublicClient(WebClient.builder(), props), props);
    }

    @AfterAll
    static void stop() throws Exception {
        server.shutdown();
    }

    static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/solana/" + name), StandardCharsets.UTF_8);
    }

    @Test
    void solUsdcResolvesToTheRaydiumPoolAndFillsTheSnapshot() throws Exception {
        server.enqueue(json(fixture("geckoterminal-pool.json")));
        StepVerifier.create(provider.snapshot("SOL/USDC"))
                .assertNext(s -> {
                    assertThat(s.symbol()).isEqualTo("SOLUSDC");
                    assertThat(s.source()).isEqualTo(SolanaSnapshotProvider.ID);
                    assertThat(s.price()).isBetween(50d, 1000d);
                    assertThat(s.volumeBase24h()).isPositive();
                    assertThat(s.observedAt()).isNotNull();
                })
                .verifyComplete();
        assertThat(server.takeRequest().getPath()).contains(SolanaMarketProperties.DEFAULT_SOL_USDC_POOL);
    }

    @Test
    void poolFailureFallsBackToJupiterSpot() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json(fixture("jupiter-price-v3.json")));
        StepVerifier.create(provider.snapshot("SOLUSDC"))
                .assertNext(s -> {
                    assertThat(s.source()).isEqualTo(SolanaSnapshotProvider.ID_JUPITER_ONLY);
                    assertThat(s.price()).isBetween(50d, 1000d);
                    assertThat(s.volumeBase24h()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void unknownPairWithNoMintFallsBackDeterministically() {
        StepVerifier.create(provider.snapshot("FOOBAR"))
                .assertNext(s -> assertThat(s.source()).isEqualTo(DeterministicLocalSnapshotProvider.ID))
                .verifyComplete();
    }

    @Test
    void aRawPoolAddressIsAcceptedAsSymbol() throws Exception {
        server.enqueue(json(fixture("geckoterminal-pool.json")));
        StepVerifier.create(provider.snapshot(SolanaMarketProperties.DEFAULT_SOL_USDC_POOL))
                .assertNext(s -> assertThat(s.source()).isEqualTo(SolanaSnapshotProvider.ID))
                .verifyComplete();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
