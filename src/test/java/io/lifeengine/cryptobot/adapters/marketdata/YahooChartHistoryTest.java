package io.lifeengine.cryptobot.adapters.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.backtest.BacktestProperties;
import io.lifeengine.cryptobot.trading.backtest.PriceBar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

/** Yahoo chart history (KAN-1226): closes ascending and clipped, null closes left out, errors are errors. */
class YahooChartHistoryTest {

    private static final Instant D1 = Instant.parse("2026-06-12T13:30:00Z");
    private static final Instant FROM = Instant.parse("2026-06-12T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-20T00:00:00Z");
    private MockWebServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private YahooChartHistory history() {
        return history(Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC));
    }

    private YahooChartHistory history(Clock clock) {
        BacktestProperties props = new BacktestProperties(null, null, Duration.ofSeconds(2), 5_000,
                new BacktestProperties.Stocks("http://localhost:" + server.getPort(), "life-engine-cryptobot/test", null));
        return new YahooChartHistory(WebClient.builder(), props, new ObjectMapper(), clock);
    }

    /** A clock the test moves. */
    private static final class MovingClock extends Clock {
        Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final String ONE_BAR = "{\"chart\":{\"result\":[{\"timestamp\":[" + D1.getEpochSecond() + "],"
            + "\"indicators\":{\"quote\":[{\"close\":[160.95]}]}}],\"error\":null}}";

    @Test
    void theSameBacktestAskedAgainTheSameDayDoesNotCallYahooAgain() {
        serve(ONE_BAR);
        MovingClock clock = new MovingClock(Instant.parse("2026-10-21T15:00:00Z"));
        YahooChartHistory h = history(clock);

        StepVerifier.create(h.closes("SPCX", Duration.ofDays(1), FROM, TO)).expectNextCount(1).verifyComplete();
        StepVerifier.create(h.closes("SPCX", Duration.ofDays(1), FROM, TO)).expectNextCount(1).verifyComplete();
        assertThat(requests).hasSize(1);

        // another symbol or another window is another request
        StepVerifier.create(h.closes("QQQ", Duration.ofDays(1), FROM, TO)).expectNextCount(1).verifyComplete();
        assertThat(requests).hasSize(2);

        // the next UTC day starts over
        clock.now = Instant.parse("2026-10-22T00:01:00Z");
        StepVerifier.create(h.closes("SPCX", Duration.ofDays(1), FROM, TO)).expectNextCount(1).verifyComplete();
        assertThat(requests).hasSize(3);
    }

    @Test
    void aFailureIsNotCachedTheNextAskRetries() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return calls.incrementAndGet() == 1
                        ? new MockResponse().setResponseCode(429)
                        : new MockResponse().setHeader("Content-Type", "application/json").setBody(ONE_BAR);
            }
        });
        YahooChartHistory h = history();

        StepVerifier.create(h.closes("SPCX", Duration.ofDays(1), FROM, TO)).expectError().verify();
        StepVerifier.create(h.closes("SPCX", Duration.ofDays(1), FROM, TO)).expectNextCount(1).verifyComplete();
        assertThat(calls).hasValue(2);
    }

    private void serve(String body) {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                requests.add(request);
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
            }
        });
    }

    private static long t(int day) {
        return D1.plusSeconds(86_400L * day).getEpochSecond();
    }

    @Test
    void parsesClosesAscendingSkipsNullsAndSendsAnIdentifiableUserAgent() throws Exception {
        // out of order, one null close (a halted session), one bar after `to`
        serve("{\"chart\":{\"result\":[{\"timestamp\":[" + t(1) + "," + t(0) + "," + t(2) + "," + t(10) + "],"
                + "\"indicators\":{\"quote\":[{\"close\":[171.2,160.95,null,150.0]}]}}],\"error\":null}}");

        StepVerifier.create(history().closes("SPCX", Duration.ofDays(1), FROM, TO))
                .assertNext(bars -> assertThat(bars).containsExactly(
                        new PriceBar(D1, new BigDecimal("160.95")), new PriceBar(D1.plusSeconds(86_400), new BigDecimal("171.2"))))
                .verifyComplete();
        RecordedRequest r = requests.get(0);
        assertThat(r.getPath()).startsWith("/v8/finance/chart/SPCX?period1=" + FROM.getEpochSecond() + "&period2=" + TO.getEpochSecond()
                + "&interval=1d");
        assertThat(r.getHeader("User-Agent")).isEqualTo("life-engine-cryptobot/test");
    }

    @Test
    void hourlyMapsTo60m() {
        serve("{\"chart\":{\"result\":[{\"timestamp\":[],\"indicators\":{\"quote\":[{\"close\":[]}]}}],\"error\":null}}");

        StepVerifier.create(history().closes("QQQ", Duration.ofHours(1), FROM, TO)).assertNext(bars -> assertThat(bars).isEmpty()).verifyComplete();
        assertThat(requests.get(0).getPath()).contains("interval=60m");
    }

    @Test
    void aChartErrorIsAnErrorNotAnEmptySeries() {
        serve("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No data found, symbol may be delisted\"}}}");

        StepVerifier.create(history().closes("NOPE", Duration.ofDays(1), FROM, TO))
                .expectErrorSatisfies(ex -> assertThat(ex).hasMessageContaining("symbol may be delisted"))
                .verify();
    }

    @Test
    void anHttpErrorIsAnError() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(429);
            }
        });

        StepVerifier.create(history().closes("SPCX", Duration.ofDays(1), FROM, TO)).expectError().verify();
    }

    @Test
    void rejectsABadSymbolOrGranularityWithoutCallingYahoo() {
        StepVerifier.create(history().closes("spcx; drop", Duration.ofDays(1), FROM, TO)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(history().closes("SPCX", Duration.ofMinutes(5), FROM, TO)).expectError(IllegalArgumentException.class).verify();
        assertThat(requests).isEmpty();
    }
}
