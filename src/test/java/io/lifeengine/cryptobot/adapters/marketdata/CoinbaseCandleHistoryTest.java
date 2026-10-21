package io.lifeengine.cryptobot.adapters.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.backtest.BacktestProperties;
import io.lifeengine.cryptobot.trading.backtest.PriceBar;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** Coinbase candles (KAN-1216): paged, sorted ascending, de-duplicated, clipped to [from, to); a failure is an error. */
class CoinbaseCandleHistoryTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private MockWebServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private CoinbaseCandleHistory history(int maxBars) {
        BacktestProperties props = new BacktestProperties("http://localhost:" + server.getPort(), "SOL-USD", Duration.ofSeconds(2), maxBars);
        return new CoinbaseCandleHistory(WebClient.builder(), props, new ObjectMapper());
    }

    /** Answers every page with one candle per hour of its window, newest first, close = 100 + hour index. */
    private void serveHourly() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                paths.add(request.getPath());
                Instant start = Instant.parse(request.getRequestUrl().queryParameter("start"));
                Instant end = Instant.parse(request.getRequestUrl().queryParameter("end"));
                StringBuilder body = new StringBuilder("[");
                for (Instant t = end; !t.isBefore(start); t = t.minusSeconds(3600)) {
                    long hour = (t.getEpochSecond() - FROM.getEpochSecond()) / 3600;
                    if (body.length() > 1) {
                        body.append(',');
                    }
                    body.append('[').append(t.getEpochSecond()).append(",1,2,3,").append(100 + hour).append(".25,9.5]");
                }
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(body.append(']').toString());
            }
        });
    }

    @Test
    void pagesAWindowLongerThan300BarsAndReturnsItAscendingAndClipped() {
        serveHourly();
        Instant to = FROM.plusSeconds(3600L * 400);

        StepVerifier.create(history(5_000).closes("SOL-USD", Duration.ofHours(1), FROM, to))
                .assertNext(bars -> {
                    assertThat(bars).hasSize(400);
                    assertThat(bars.get(0)).isEqualTo(new PriceBar(FROM, new java.math.BigDecimal("100.25")));
                    assertThat(bars.get(399).time()).isEqualTo(to.minusSeconds(3600));
                    assertThat(bars.get(399).close()).isEqualByComparingTo("499.25");
                    for (int i = 1; i < bars.size(); i++) {
                        assertThat(bars.get(i).time()).isAfter(bars.get(i - 1).time());
                    }
                })
                .verifyComplete();
        assertThat(paths).hasSize(2);
        assertThat(paths.get(0)).startsWith("/products/SOL-USD/candles?granularity=3600&start=2026-09-01T00:00:00Z");
    }

    @Test
    void aFailedPageFailsTheWholeSeries() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(429);
            }
        });

        StepVerifier.create(history(5_000).closes("SOL-USD", Duration.ofHours(1), FROM, FROM.plusSeconds(3600L * 10)))
                .expectError()
                .verify();
    }

    @Test
    void rejectsAnUnsupportedGranularityAndAWindowOverMaxBars() {
        StepVerifier.create(history(5_000).closes("SOL-USD", Duration.ofMinutes(2), FROM, FROM.plusSeconds(3600)))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(history(100).closes("SOL-USD", Duration.ofHours(1), FROM, FROM.plusSeconds(3600L * 101)))
                .expectError(IllegalArgumentException.class)
                .verify();
        assertThat(paths).isEmpty();
    }
}
