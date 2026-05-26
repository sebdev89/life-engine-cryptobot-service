package io.lifeengine.cryptobot.infrastructure.binance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class BinancePublicClientTest {

    private MockWebServer mockServer;
    private BinancePublicClient client;

    @BeforeEach
    void startMock() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        BinanceProperties properties = new BinanceProperties("http://localhost:" + mockServer.getPort(), Duration.ofSeconds(2));
        WebClient webClient = WebClient.builder().baseUrl(properties.baseUrl()).build();
        client = new BinancePublicClient(webClient, properties);
    }

    @AfterEach
    void stopMock() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void ticker24h_returnsParsedSnapshot() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{"
                        + "\"symbol\":\"BTCUSDT\","
                        + "\"lastPrice\":\"67800.50\","
                        + "\"priceChangePercent\":\"1.25\","
                        + "\"highPrice\":\"68500.00\","
                        + "\"lowPrice\":\"66700.00\","
                        + "\"quoteVolume\":\"28500000000.0\""
                        + "}"));

        StepVerifier.create(client.ticker24h("BTCUSDT"))
                .expectNextMatches(t ->
                        t.lastPrice().doubleValue() == 67800.5
                                && t.change24hPct().doubleValue() == 1.25
                                && t.quoteVolume24h().doubleValue() == 28500000000.0)
                .verifyComplete();
    }

    @Test
    void ticker24h_returnsEmptyOn5xx() {
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("upstream sad"));

        StepVerifier.create(client.ticker24h("BTCUSDT")).verifyComplete();
    }

    @Test
    void ticker24h_returnsEmptyWhenBlankSymbol() {
        StepVerifier.create(client.ticker24h("")).verifyComplete();
        StepVerifier.create(client.ticker24h(null)).verifyComplete();
        assertThat(mockServer.getRequestCount()).isZero();
    }
}
