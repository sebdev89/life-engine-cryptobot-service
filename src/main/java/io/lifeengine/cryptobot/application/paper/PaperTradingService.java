package io.lifeengine.cryptobot.application.paper;

import io.lifeengine.cryptobot.adapters.marketdata.CoinbaseCandleHistory;
import io.lifeengine.cryptobot.adapters.marketdata.CoinbaseSpotPriceSource;
import io.lifeengine.cryptobot.adapters.marketdata.JupiterPriceClient;
import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.application.backtest.BacktestProperties;
import io.lifeengine.cryptobot.application.paper.PaperProperties.PortfolioDef;
import io.lifeengine.cryptobot.application.paper.PaperRepository.Portfolio;
import io.lifeengine.cryptobot.application.paper.PaperRepository.PriceCheck;
import io.lifeengine.cryptobot.application.paper.PaperRepository.Tick;
import io.lifeengine.cryptobot.core.oracle.PriceObservation;
import io.lifeengine.cryptobot.trading.backtest.BacktestResult;
import io.lifeengine.cryptobot.trading.backtest.BacktestStrategy;
import io.lifeengine.cryptobot.trading.backtest.BacktestStrategy.Holdings;
import io.lifeengine.cryptobot.trading.backtest.Backtester;
import io.lifeengine.cryptobot.trading.backtest.Execution;
import io.lifeengine.cryptobot.trading.backtest.FeeModel;
import io.lifeengine.cryptobot.trading.backtest.Performance;
import io.lifeengine.cryptobot.trading.backtest.PriceBar;
import io.lifeengine.cryptobot.trading.backtest.Strategies;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * CryptoBot v3 paper trading (KAN-1217): the v2 strategies run forward on live closed SOL/USD candles with the
 * same {@link Execution} fill model as the backtester. No wallet, no signer, no chain, no money: every decision is
 * a row in {@code paper_tick} and the verdict ("beats doing nothing, net of costs") is computed from those rows.
 *
 * <p>One {@link #tick} applies every closed candle a portfolio has not seen yet (up to {@code maxCatchUpBars},
 * so a restart catches up instead of skipping), in order, idempotently: a candle already applied is refused by
 * the store. A new portfolio starts at the latest closed candle — never in the past. Each tick also records
 * Coinbase spot next to Jupiter's on-chain price (the CEX-vs-Solana gap the backtest cannot see).
 */
@Service
public class PaperTradingService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingService.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final Map<String, String> SOL = Map.of(TokenRegistry.NATIVE_SOL_MINT, "SOL");

    public static final String METRIC_EQUITY = "cryptobot.paper.equity.usd";
    public static final String METRIC_RETURN = "cryptobot.paper.return.pct";
    public static final String METRIC_TICKS = "cryptobot.paper.ticks";
    public static final String METRIC_LAST_BAR = "cryptobot.paper.last.bar.epoch.seconds";
    public static final String METRIC_DEVIATION = "cryptobot.paper.price.deviation.bps";

    private final CoinbaseCandleHistory history;
    private final JupiterPriceClient jupiter;
    private final CoinbaseSpotPriceSource coinbaseSpot;
    private final PaperRepository repo;
    private final PaperProperties props;
    private final String product;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

    @Autowired
    public PaperTradingService(CoinbaseCandleHistory history, JupiterPriceClient jupiter, CoinbaseSpotPriceSource coinbaseSpot,
            PaperRepository repo, PaperProperties props, BacktestProperties backtest, MeterRegistry meters) {
        this(history, jupiter, coinbaseSpot, repo, props, backtest, meters, Clock.systemUTC());
    }

    public PaperTradingService(CoinbaseCandleHistory history, JupiterPriceClient jupiter, CoinbaseSpotPriceSource coinbaseSpot,
            PaperRepository repo, PaperProperties props, BacktestProperties backtest, MeterRegistry meters, Clock clock) {
        this.history = history;
        this.jupiter = jupiter;
        this.coinbaseSpot = coinbaseSpot;
        this.repo = repo;
        this.props = props;
        this.product = backtest.product();
        this.meters = meters;
        this.clock = clock;
    }

    /** What one portfolio did in one {@link #tick}. */
    public enum Outcome { ADVANCED, UP_TO_DATE, PARAMS_CHANGED }

    public record Advance(String portfolioId, Outcome outcome, int candles) {}

    public record TickResult(Instant closedUpTo, int candles, List<Advance> portfolios) {}

    /** Applies every closed candle each configured portfolio has not seen yet. */
    public Mono<TickResult> tick() {
        long g = props.granularity().toSeconds();
        Instant to = Instant.ofEpochSecond(clock.instant().getEpochSecond() / g * g);
        Instant floor = to.minusSeconds(g * props.maxCatchUpBars());
        return repo.findAll().collectMap(Portfolio::id).flatMap(stored -> {
            Instant from = to.minusSeconds(g);
            for (PortfolioDef def : props.portfolios()) {
                Portfolio p = stored.get(def.id());
                if (p != null && p.lastBarTime().plusSeconds(g).isBefore(from)) {
                    from = p.lastBarTime().plusSeconds(g);
                }
            }
            Instant start = from.isBefore(floor) ? floor : from;
            return history.closes(product, props.granularity(), start, to)
                    .flatMap(bars -> Flux.fromIterable(props.portfolios())
                            .concatMap(def -> advance(def, stored.get(def.id()), bars))
                            .collectList()
                            .flatMap(advances -> (bars.isEmpty() ? Mono.<Void>empty() : priceCheck(bars.get(bars.size() - 1).time()))
                                    .thenReturn(new TickResult(to, bars.size(), advances))));
        });
    }

    private Mono<Advance> advance(PortfolioDef def, Portfolio stored, List<PriceBar> bars) {
        BacktestStrategy strategy = Strategies.named(def.strategy(), def.targetSolWeight(), def.bandBps(), def.dcaAmountUsd(), def.dcaEveryBars());
        String params = Strategies.params(strategy);
        if (stored != null && !matches(stored, strategy, params)) {
            log.error("paper_params_changed portfolio={} frozen=[{} {} fee={} slip={} usd={}] configured=[{} {} fee={} slip={} usd={}]",
                    def.id(), stored.strategy(), stored.params(), stored.feeBps(), stored.slippageBps(), stored.initialUsd(),
                    strategy.id(), params, props.feeBps(), props.slippageBps(), props.initialUsd());
            meters.counter(METRIC_TICKS, "portfolio", def.id(), "result", "params_changed").increment();
            return Mono.just(new Advance(def.id(), Outcome.PARAMS_CHANGED, 0));
        }
        List<PriceBar> pending = new ArrayList<>();
        if (stored == null) {
            if (!bars.isEmpty()) {
                pending.add(bars.get(bars.size() - 1));
            }
        } else {
            bars.stream().filter(b -> b.time().isAfter(stored.lastBarTime())).forEach(pending::add);
        }
        if (pending.isEmpty()) {
            return Mono.just(new Advance(def.id(), Outcome.UP_TO_DATE, 0));
        }
        FeeModel fees = new FeeModel(props.feeBps(), props.slippageBps());
        AtomicReference<Portfolio> state = new AtomicReference<>(stored);
        return Flux.fromIterable(pending)
                .concatMap(bar -> {
                    Portfolio before = state.get();
                    Step step = step(def.id(), strategy, params, fees, before, bar);
                    return repo.record(step.after(), step.tick(), before == null).then(Mono.fromRunnable(() -> {
                        state.set(step.after());
                        publish(def.id(), step.after(), step.tick().equityUsd());
                    }));
                })
                .then(Mono.fromSupplier(() -> {
                    meters.counter(METRIC_TICKS, "portfolio", def.id(), "result", "advanced").increment(pending.size());
                    log.info("paper_advanced portfolio={} candles={} last_bar={}", def.id(), pending.size(), state.get().lastBarTime());
                    return new Advance(def.id(), Outcome.ADVANCED, pending.size());
                }));
    }

    /** One candle applied: the portfolio state after it and the row that records the decision. */
    record Step(Portfolio after, Tick tick) {}

    /** Pure: allocates on the first candle, then decides at the close and fills with {@link Execution}, like the backtester. */
    private Step step(String id, BacktestStrategy strategy, String params, FeeModel fees, Portfolio before, PriceBar bar) {
        Holdings h = before == null
                ? Execution.allocate(props.initialUsd(), strategy.initialSolWeight(), bar.close())
                : new Holdings(before.sol(), before.usdc());
        int index = before == null ? 0 : before.ticks();
        BigDecimal notional = strategy.decide(index, bar, h);
        Execution.Fill fill = Execution.fill(h, notional, bar.close(), fees);
        Performance.Mark mark = Backtester.mark(fill.after(), bar.close());
        Portfolio after = new Portfolio(
                id,
                strategy.id(),
                params,
                fees.feeBps(),
                fees.slippageBps(),
                before == null ? props.initialUsd().setScale(6, RoundingMode.DOWN) : before.initialUsd(),
                fill.after().sol(),
                fill.after().usdc(),
                index + 1,
                (before == null ? 0 : before.trades()) + (fill.traded() ? 1 : 0),
                (before == null ? BigDecimal.ZERO : before.costsUsd()).add(fill.costUsd()).setScale(6, RoundingMode.UP),
                before == null ? bar.time() : before.startedAt(),
                bar.time());
        Tick tick = new Tick(id, bar.time(), bar.close(),
                notional == null ? BigDecimal.ZERO.setScale(6) : notional.setScale(6, RoundingMode.DOWN),
                fill.traded(), fill.costUsd().setScale(6, RoundingMode.UP), after.sol(), after.usdc(),
                mark.equityUsd(), mark.solWeight().setScale(10, RoundingMode.HALF_EVEN));
        return new Step(after, tick);
    }

    private boolean matches(Portfolio p, BacktestStrategy strategy, String params) {
        return p.strategy().equals(strategy.id())
                && p.params().equals(params)
                && p.feeBps() == props.feeBps()
                && p.slippageBps() == props.slippageBps()
                && p.initialUsd().compareTo(props.initialUsd()) == 0;
    }

    private Mono<Void> priceCheck(Instant barTime) {
        return Mono.zip(sol(jupiter.observe(SOL)), sol(coinbaseSpot.observe(SOL)))
                .flatMap(t -> {
                    BigDecimal jup = t.getT1().orElse(null);
                    BigDecimal cb = t.getT2().orElse(null);
                    Integer dev = jup == null || cb == null ? null
                            : jup.subtract(cb).multiply(BigDecimal.valueOf(10_000)).divide(cb, 0, RoundingMode.HALF_EVEN).intValueExact();
                    if (dev != null) {
                        gauge(METRIC_DEVIATION, "SOL").set(dev.doubleValue());
                    }
                    return repo.recordPriceCheck(new PriceCheck(barTime, cb, jup, dev, clock.instant()));
                })
                .onErrorResume(ex -> {
                    log.warn("paper_price_check_failed bar={} error={}", barTime, ex.toString());
                    return Mono.empty();
                });
    }

    private static Mono<Optional<BigDecimal>> sol(Mono<List<PriceObservation>> obs) {
        return obs.map(list -> list.stream().filter(o -> "SOL".equals(o.asset())).map(PriceObservation::priceUsd).findFirst())
                .onErrorReturn(Optional.empty())
                .defaultIfEmpty(Optional.empty());
    }

    private void publish(String id, Portfolio p, BigDecimal equity) {
        gauge(METRIC_EQUITY, id).set(equity.doubleValue());
        gauge(METRIC_RETURN, id).set(equity.subtract(p.initialUsd()).multiply(BigDecimal.valueOf(100)).divide(p.initialUsd(), MC).doubleValue());
        gauge(METRIC_LAST_BAR, id).set((double) p.lastBarTime().getEpochSecond());
    }

    private AtomicReference<Double> gauge(String name, String tag) {
        return gauges.computeIfAbsent(name + "|" + tag, k -> {
            AtomicReference<Double> ref = new AtomicReference<>(Double.NaN);
            Gauge.builder(name, ref, AtomicReference::get).tag(METRIC_DEVIATION.equals(name) ? "asset" : "portfolio", tag).register(meters);
            return ref;
        });
    }

    // ---- read side --------------------------------------------------------------------------------------------

    /** The state of one portfolio window and, from two ticks on, its metrics computed from the stored ticks. */
    public record PortfolioReport(
            String id,
            String status,
            String strategy,
            String params,
            Instant startedAt,
            Instant lastBarTime,
            int ticks,
            Duration window,
            BacktestResult metrics,
            Boolean beatsHoldSol,
            Boolean beatsHoldUsdc) {}

    public record PriceGap(int checks, int withBothPrices, Double meanAbsDeviationBps, Integer maxAbsDeviationBps) {}

    public record Report(boolean enabled, int feeBps, int slippageBps, List<PortfolioReport> portfolios, PriceGap priceGap) {}

    public Mono<Report> report() {
        Mono<List<PortfolioReport>> portfolios = repo.findAll()
                .concatMap(p -> repo.ticks(p.id()).collectList().map(ticks -> measured(p, ticks)))
                .collectList()
                .map(this::compared);
        Mono<PriceGap> gap = repo.priceChecks().collectList().map(PaperTradingService::gap);
        return Mono.zip(portfolios, gap).map(t -> new Report(props.enabled(), props.feeBps(), props.slippageBps(), t.getT1(), t.getT2()));
    }

    private PortfolioReport measured(Portfolio p, List<Tick> ticks) {
        Optional<PortfolioDef> def = props.portfolios().stream().filter(d -> d.id().equals(p.id())).findFirst();
        String status = def.map(d -> {
            BacktestStrategy s = Strategies.named(d.strategy(), d.targetSolWeight(), d.bandBps(), d.dcaAmountUsd(), d.dcaEveryBars());
            return matches(p, s, Strategies.params(s)) ? "ACTIVE" : "PARAMS_CHANGED";
        }).orElse("RETIRED");
        BacktestResult metrics = null;
        if (ticks.size() >= 2) {
            List<PriceBar> bars = ticks.stream().map(t -> new PriceBar(t.barTime(), t.close())).toList();
            List<Performance.Mark> marks = ticks.stream().map(t -> new Performance.Mark(t.equityUsd(), t.solWeight())).toList();
            metrics = Performance.measure(p.strategy(), bars, p.initialUsd(), marks, p.trades(), p.costsUsd(), new Holdings(p.sol(), p.usdc()));
        }
        return new PortfolioReport(p.id(), status, p.strategy(), p.params(), p.startedAt(), p.lastBarTime(), p.ticks(),
                Duration.between(p.startedAt(), p.lastBarTime()), metrics, null, null);
    }

    /** Fills beatsHoldSol / beatsHoldUsdc against the benchmark portfolios that cover the exact same window. */
    private List<PortfolioReport> compared(List<PortfolioReport> all) {
        List<PortfolioReport> out = new ArrayList<>();
        for (PortfolioReport r : all) {
            out.add(new PortfolioReport(r.id(), r.status(), r.strategy(), r.params(), r.startedAt(), r.lastBarTime(), r.ticks(), r.window(),
                    r.metrics(), beats(r, all, "hold-sol"), beats(r, all, "hold-usdc")));
        }
        return out;
    }

    private static Boolean beats(PortfolioReport r, List<PortfolioReport> all, String benchmark) {
        if (r.metrics() == null || r.strategy().equals(benchmark)) {
            return null;
        }
        return all.stream()
                .filter(b -> b.strategy().equals(benchmark) && b.metrics() != null
                        && b.startedAt().equals(r.startedAt()) && b.lastBarTime().equals(r.lastBarTime()))
                .findFirst()
                .map(b -> r.metrics().finalUsd().compareTo(b.metrics().finalUsd()) > 0)
                .orElse(null);
    }

    private static PriceGap gap(List<PriceCheck> checks) {
        List<Integer> devs = checks.stream().map(PriceCheck::deviationBps).filter(d -> d != null).toList();
        if (devs.isEmpty()) {
            return new PriceGap(checks.size(), 0, null, null);
        }
        double mean = devs.stream().mapToInt(Math::abs).average().orElse(0);
        int max = devs.stream().mapToInt(Math::abs).max().orElse(0);
        return new PriceGap(checks.size(), devs.size(), Math.round(mean * 100) / 100.0, max);
    }
}
