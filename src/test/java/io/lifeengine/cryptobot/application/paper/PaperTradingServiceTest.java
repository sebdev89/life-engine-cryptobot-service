package io.lifeengine.cryptobot.application.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.marketdata.CoinbaseCandleHistory;
import io.lifeengine.cryptobot.adapters.marketdata.CoinbaseSpotPriceSource;
import io.lifeengine.cryptobot.adapters.marketdata.JupiterPriceClient;
import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.application.backtest.BacktestProperties;
import io.lifeengine.cryptobot.application.paper.PaperProperties.PortfolioDef;
import io.lifeengine.cryptobot.application.paper.PaperRepository.Tick;
import io.lifeengine.cryptobot.application.paper.PaperTradingService.Outcome;
import io.lifeengine.cryptobot.testsupport.InMemoryPaperRepository;
import io.lifeengine.cryptobot.trading.backtest.BacktestResult;
import io.lifeengine.cryptobot.trading.backtest.Backtester;
import io.lifeengine.cryptobot.trading.backtest.FeeModel;
import io.lifeengine.cryptobot.trading.backtest.PriceBar;
import io.lifeengine.cryptobot.trading.backtest.Strategies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Paper trading (KAN-1217) over a fake Coinbase + Jupiter and an in-memory store, with a clock the test moves.
 * SOL closes 100 at 11:00, 110 at 12:00, 120 at 13:00 and 90 at 14:00 (UTC, 2026-10-20).
 */
class PaperTradingServiceTest {

    private static final Instant DAY = Instant.parse("2026-10-20T00:00:00Z");
    private static final Map<Instant, String> CLOSES = new TreeMap<>(Map.of(
            DAY.plusSeconds(11 * 3600), "100", DAY.plusSeconds(12 * 3600), "110",
            DAY.plusSeconds(13 * 3600), "120", DAY.plusSeconds(14 * 3600), "90"));

    private MockWebServer server;
    private InMemoryPaperRepository repo;
    private SimpleMeterRegistry meters;

    /** A clock the tests move forward. */
    private static final class MovingClock extends Clock {
        Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/products/SOL-USD/candles")) {
                    Instant from = Instant.parse(request.getRequestUrl().queryParameter("start"));
                    Instant to = Instant.parse(request.getRequestUrl().queryParameter("end"));
                    StringBuilder body = new StringBuilder("[");
                    CLOSES.entrySet().stream()
                            .filter(e -> !e.getKey().isBefore(from) && !e.getKey().isAfter(to))
                            .sorted(Map.Entry.<Instant, String>comparingByKey().reversed())
                            .forEach(e -> body.append(body.length() > 1 ? "," : "")
                                    .append('[').append(e.getKey().getEpochSecond()).append(",1,2,3,").append(e.getValue()).append(",9]"));
                    return json(body.append(']').toString());
                }
                if (path.startsWith("/price/v3")) {
                    return json("{\"" + TokenRegistry.NATIVE_SOL_MINT + "\":{\"usdPrice\":100.5}}");
                }
                if (path.equals("/v2/prices/SOL-USD/spot")) {
                    return json("{\"data\":{\"amount\":\"100.00\",\"base\":\"SOL\",\"currency\":\"USD\"}}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
        repo = new InMemoryPaperRepository();
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private PaperTradingService service(Clock clock, List<PortfolioDef> portfolios) {
        String base = "http://localhost:" + server.getPort();
        ObjectMapper om = new ObjectMapper();
        BacktestProperties bt = new BacktestProperties(base, "SOL-USD", Duration.ofSeconds(2), 5_000);
        MarketDataProperties md = new MarketDataProperties(base, Duration.ofSeconds(2), Map.of(), Map.of(), true,
                new MarketDataProperties.Pyth(false, base, Map.of()), new MarketDataProperties.CoinGecko(false, base, Map.of()),
                new MarketDataProperties.Coinbase(true, base, Map.of("SOL", "SOL-USD")), null);
        PaperProperties props = new PaperProperties(true, null, null, null, null, null, null, portfolios, null);
        return new PaperTradingService(new CoinbaseCandleHistory(WebClient.builder(), bt, om), new JupiterPriceClient(WebClient.builder(), md, om),
                new CoinbaseSpotPriceSource(WebClient.builder(), md, om), repo, props, bt, meters, clock);
    }

    private PaperTradingService service(Clock clock) {
        return service(clock, null);
    }

    @Test
    @DisplayName("a new portfolio starts at the latest closed candle, never in the past; the in-progress candle is not used")
    void startsAtTheLatestClosedCandle() {
        // 12:05 → 12:00 is in progress, 11:00 is the latest closed
        PaperTradingService.TickResult r = service(new MovingClock(DAY.plusSeconds(12 * 3600 + 300))).tick().block();

        assertThat(r.portfolios()).extracting(PaperTradingService.Advance::outcome).containsOnly(Outcome.ADVANCED);
        assertThat(repo.find("hold-sol").block().lastBarTime()).isEqualTo(DAY.plusSeconds(11 * 3600));
        assertThat(repo.find("hold-sol").block().sol()).isEqualByComparingTo("10");
        // DCA buys 50 USD at its first close (index 0), like in the backtest
        assertThat(repo.find("dca-50-24").block().trades()).isEqualTo(1);
        assertThat(repo.find("dca-50-24").block().usdc()).isEqualByComparingTo("950");
    }

    @Test
    @DisplayName("catches up every closed candle it missed, in order, and a second tick in the same hour changes nothing")
    void catchesUpAndIsIdempotent() {
        MovingClock clock = new MovingClock(DAY.plusSeconds(12 * 3600 + 300));
        PaperTradingService s = service(clock);
        s.tick().block();

        clock.now = DAY.plusSeconds(15 * 3600 + 300);
        PaperTradingService.TickResult r = s.tick().block();
        PaperTradingService.TickResult again = s.tick().block();

        assertThat(r.portfolios()).allSatisfy(a -> assertThat(a.candles()).isEqualTo(3));
        assertThat(again.portfolios()).extracting(PaperTradingService.Advance::outcome).containsOnly(Outcome.UP_TO_DATE);
        assertThat(repo.ticks("hold-sol").collectList().block()).extracting(Tick::barTime)
                .containsExactly(DAY.plusSeconds(11 * 3600), DAY.plusSeconds(12 * 3600), DAY.plusSeconds(13 * 3600), DAY.plusSeconds(14 * 3600));
        assertThat(repo.find("hold-sol").block().ticks()).isEqualTo(4);
    }

    @Test
    @DisplayName("KAN-1276: when a tick runs does not change what it decides — same candles, ticks at :00:31 or :04:59, same rows")
    void timingDoesNotChangeDecisions() {
        // run 1: ticks right after the closes (what the clock-aligned job does)
        MovingClock early = new MovingClock(DAY.plusSeconds(12 * 3600 + 31));
        PaperTradingService a = service(early);
        a.tick().block();
        early.now = DAY.plusSeconds(15 * 3600 + 31);
        a.tick().block();
        java.util.Map<String, List<Tick>> first = new java.util.TreeMap<>();
        for (String id : List.of("hold-sol", "hold-usdc", "band-50-500", "dca-50-24")) {
            first.put(id, repo.ticks(id).collectList().block());
        }

        // run 2, from scratch: ticks late in the cycle, and the late one catches up three candles at once
        repo = new InMemoryPaperRepository();
        MovingClock late = new MovingClock(DAY.plusSeconds(12 * 3600 + 299));
        PaperTradingService b = service(late);
        b.tick().block();
        late.now = DAY.plusSeconds(15 * 3600 + 299);
        b.tick().block();

        for (String id : first.keySet()) {
            assertThat(repo.ticks(id).collectList().block()).as(id).isEqualTo(first.get(id)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("paper and backtest are the same model: a band portfolio ends exactly where the backtester ends on the same closes")
    void paperEqualsBacktest() {
        MovingClock clock = new MovingClock(DAY.plusSeconds(12 * 3600 + 300));
        // ± 2 %: SOL at 120 puts the weight at 54.5 % and at 90 below 48 %, so this band trades
        PaperTradingService s = service(clock, List.of(new PortfolioDef("band-50-200", "band-rebalance", new BigDecimal("0.5"), 200, null, null)));
        s.tick().block();
        clock.now = DAY.plusSeconds(15 * 3600 + 300);
        s.tick().block();

        List<PriceBar> bars = CLOSES.entrySet().stream().map(e -> new PriceBar(e.getKey(), new BigDecimal(e.getValue()))).toList();
        BacktestResult backtest = Backtester.run(bars, Strategies.bandRebalance(new BigDecimal("0.5"), 200), new BigDecimal("1000"), FeeModel.DEFAULT);
        PaperRepository.Portfolio paper = repo.find("band-50-200").block();

        assertThat(paper.trades()).isEqualTo(backtest.trades()).isPositive();
        assertThat(paper.sol()).isEqualByComparingTo(backtest.finalSol());
        assertThat(paper.usdc()).isEqualByComparingTo(backtest.finalUsdc());
    }

    @Test
    @DisplayName("knobs changed after the first tick: the portfolio is not advanced and is reported PARAMS_CHANGED")
    void frozenParams() {
        MovingClock clock = new MovingClock(DAY.plusSeconds(12 * 3600 + 300));
        service(clock).tick().block();

        clock.now = DAY.plusSeconds(15 * 3600 + 300);
        PaperTradingService changed = service(clock, List.of(new PortfolioDef("band-50-500", "band-rebalance", new BigDecimal("0.5"), 1_000, null, null)));
        PaperTradingService.TickResult r = changed.tick().block();

        assertThat(r.portfolios()).singleElement().extracting(PaperTradingService.Advance::outcome).isEqualTo(Outcome.PARAMS_CHANGED);
        assertThat(repo.find("band-50-500").block().ticks()).isEqualTo(1);
        assertThat(changed.report().block().portfolios())
                .filteredOn(p -> p.id().equals("band-50-500")).singleElement()
                .extracting(PaperTradingService.PortfolioReport::status).isEqualTo("PARAMS_CHANGED");
    }

    @Test
    @DisplayName("report: metrics from the stored ticks, benchmarks over the same window, and the Coinbase-vs-Jupiter gap")
    void report() {
        MovingClock clock = new MovingClock(DAY.plusSeconds(12 * 3600 + 300));
        PaperTradingService s = service(clock);
        s.tick().block();
        clock.now = DAY.plusSeconds(15 * 3600 + 300);
        s.tick().block();

        PaperTradingService.Report report = s.report().block();
        PaperTradingService.PortfolioReport sol = report.portfolios().stream().filter(p -> p.id().equals("hold-sol")).findFirst().orElseThrow();
        PaperTradingService.PortfolioReport usdc = report.portfolios().stream().filter(p -> p.id().equals("hold-usdc")).findFirst().orElseThrow();
        PaperTradingService.PortfolioReport band = report.portfolios().stream().filter(p -> p.id().equals("band-50-500")).findFirst().orElseThrow();

        // 100 → 90: holding SOL loses 10 %, holding USDC is flat
        assertThat(sol.metrics().returnPct()).isEqualByComparingTo("-10");
        assertThat(usdc.metrics().returnPct()).isEqualByComparingTo("0");
        assertThat(sol.window()).isEqualTo(Duration.ofHours(3));
        assertThat(band.beatsHoldSol()).isTrue();
        assertThat(band.beatsHoldUsdc()).isFalse();
        assertThat(sol.beatsHoldSol()).isNull();
        // Jupiter 100.5 vs Coinbase spot 100.00 → +50 bps, one check per tick that saw candles
        assertThat(report.priceGap().checks()).isEqualTo(2);
        assertThat(report.priceGap().maxAbsDeviationBps()).isEqualTo(50);
        assertThat(meters.find(PaperTradingService.METRIC_EQUITY).tag("portfolio", "hold-sol").gauge().value()).isEqualTo(900.0);
    }
}
