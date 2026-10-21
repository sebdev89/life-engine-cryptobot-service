package io.lifeengine.cryptobot.application.backtest;

import io.lifeengine.cryptobot.adapters.marketdata.CoinbaseCandleHistory;
import io.lifeengine.cryptobot.adapters.marketdata.YahooChartHistory;
import io.lifeengine.cryptobot.trading.backtest.BacktestReport;
import io.lifeengine.cryptobot.trading.backtest.BacktestResult;
import io.lifeengine.cryptobot.trading.backtest.BacktestStrategy;
import io.lifeengine.cryptobot.trading.backtest.Backtester;
import io.lifeengine.cryptobot.trading.backtest.FeeModel;
import io.lifeengine.cryptobot.trading.backtest.Strategies;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Runs one backtest over public history: SOL/USD (KAN-1216, CryptoBot v2) or a stock (KAN-1226). Fetches the
 * closes, builds the strategy from the request and compares it against holding the asset and holding cash
 * (USDC for crypto); a stock is also compared with an index (QQQ unless the request names another) over the same
 * window. Read-only — it never touches a wallet, a signer, a chain or a broker; a result is evidence, never an order.
 *
 * <p>For a stock the report's {@code holdSol} / {@code holdUsdc} mean "hold the asset" / "hold cash": the same
 * model, the asset is whatever {@code Run.product} names.
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);
    public static final String SOURCE = "coinbase-exchange-candles";
    public static final String SOURCE_STOCKS = "yahoo-chart";
    /** A stock trade through a retail broker: no commission, but a spread and slippage. */
    public static final FeeModel STOCK_FEES = new FeeModel(5, 5);

    private final CoinbaseCandleHistory history;
    private final YahooChartHistory stocks;
    private final BacktestProperties properties;

    public BacktestService(CoinbaseCandleHistory history, YahooChartHistory stocks, BacktestProperties properties) {
        this.history = history;
        this.stocks = stocks;
        this.properties = properties;
    }

    public Mono<Run> run(Request request) {
        BacktestStrategy strategy;
        FeeModel fees;
        boolean stock;
        try {
            stock = isStock(request.market());
            strategy = strategyOf(request);
            FeeModel defaults = stock ? STOCK_FEES : FeeModel.DEFAULT;
            fees = new FeeModel(
                    request.feeBps() == null ? defaults.feeBps() : request.feeBps(),
                    request.slippageBps() == null ? defaults.slippageBps() : request.slippageBps());
            if (request.from() == null || request.to() == null) {
                throw new IllegalArgumentException("from and to are required");
            }
            if (stock && (request.symbol() == null || request.symbol().isBlank())) {
                throw new IllegalArgumentException("market=stock needs a symbol (e.g. SPCX)");
            }
        } catch (IllegalArgumentException ex) {
            return Mono.error(ex);
        }
        BigDecimal initialUsd = request.initialUsd() == null ? new BigDecimal("1000") : request.initialUsd();
        return stock ? stock(request, strategy, fees, initialUsd) : crypto(request, strategy, fees, initialUsd);
    }

    private Mono<Run> crypto(Request request, BacktestStrategy strategy, FeeModel fees, BigDecimal initialUsd) {
        Duration granularity = Duration.ofSeconds(request.granularitySeconds() == null ? 3_600 : request.granularitySeconds());
        String product = properties.product();
        return history.closes(product, granularity, request.from(), request.to())
                .map(bars -> {
                    BacktestReport report = BacktestReport.of(bars, strategy, initialUsd, fees);
                    logRun("crypto", product, strategy, bars.size(), request, report);
                    long expected = Duration.between(request.from(), request.to()).toSeconds() / granularity.toSeconds();
                    return new Run("crypto", SOURCE, product, granularity.toSeconds(), Math.max(0, expected - bars.size()), report,
                            null, null, null);
                });
    }

    private Mono<Run> stock(Request request, BacktestStrategy strategy, FeeModel fees, BigDecimal initialUsd) {
        Duration granularity = Duration.ofSeconds(request.granularitySeconds() == null ? 86_400 : request.granularitySeconds());
        String symbol = request.symbol().trim().toUpperCase(Locale.ROOT);
        String benchmark = (request.benchmarkSymbol() == null || request.benchmarkSymbol().isBlank()
                ? properties.stocks().defaultBenchmark() : request.benchmarkSymbol()).trim().toUpperCase(Locale.ROOT);
        return Mono.zip(stocks.closes(symbol, granularity, request.from(), request.to()),
                        stocks.closes(benchmark, granularity, request.from(), request.to()))
                .map(t -> {
                    BacktestReport report = BacktestReport.of(t.getT1(), strategy, initialUsd, fees);
                    // the index held over the same window, same costs: what "just buy the market" did
                    BacktestResult index = Backtester.run(t.getT2(), Strategies.holdSol(), initialUsd, fees);
                    logRun("stock", symbol, strategy, t.getT1().size(), request, report);
                    // markets close nights and weekends: "missing" bars against the calendar mean nothing here
                    return new Run("stock", SOURCE_STOCKS, symbol, granularity.toSeconds(), null, report, benchmark, index,
                            report.strategy().finalUsd().compareTo(index.finalUsd()) > 0);
                });
    }

    private static void logRun(String market, String product, BacktestStrategy strategy, int bars, Request request, BacktestReport report) {
        log.info("backtest_run market={} product={} strategy={} bars={} from={} to={} return_pct={} beats_doing_nothing={}",
                market, product, strategy.id(), bars, request.from(), request.to(), report.strategy().returnPct(), report.beatsDoingNothing());
    }

    static boolean isStock(String market) {
        String m = market == null ? "crypto" : market.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "", "crypto" -> false;
            case "stock" -> true;
            default -> throw new IllegalArgumentException("unknown market '" + market + "' (crypto, stock)");
        };
    }

    static BacktestStrategy strategyOf(Request r) {
        return Strategies.named(r.strategy(), r.targetSolWeight(), r.bandBps(), r.dcaAmountUsd(), r.dcaEveryBars());
    }

    /**
     * Optional fields default to: crypto, 1000 USD, band 50 % ± 5 %. Crypto: SOL-USD, 1 h bars, Jupiter-like costs
     * (30 + 20 bps). Stock ({@code market=stock}, {@code symbol} required): 1 d bars, broker-like costs (5 + 5 bps),
     * compared with {@code benchmarkSymbol} (default QQQ).
     */
    public record Request(
            String strategy,
            Instant from,
            Instant to,
            Long granularitySeconds,
            BigDecimal initialUsd,
            Integer feeBps,
            Integer slippageBps,
            BigDecimal targetSolWeight,
            Integer bandBps,
            BigDecimal dcaAmountUsd,
            Integer dcaEveryBars,
            String market,
            String symbol,
            String benchmarkSymbol) {

        /** The crypto-only shape of v2. */
        public Request(String strategy, Instant from, Instant to, Long granularitySeconds, BigDecimal initialUsd, Integer feeBps,
                Integer slippageBps, BigDecimal targetSolWeight, Integer bandBps, BigDecimal dcaAmountUsd, Integer dcaEveryBars) {
            this(strategy, from, to, granularitySeconds, initialUsd, feeBps, slippageBps, targetSolWeight, bandBps, dcaAmountUsd,
                    dcaEveryBars, null, null, null);
        }
    }

    /**
     * The report plus where its prices came from, so a result can be reproduced. {@code missingBars} (crypto only):
     * candles the source does not have inside the window (Coinbase skips intervals without trades; 5 of 4320 hourly
     * bars over 180 days of SOL-USD on 2026-10-20); null for stocks, whose market closes nights and weekends. The
     * backtest runs over what exists, never over invented prices. Stocks also carry the index held over the same
     * window ({@code benchmark}, {@code benchmarkResult}) and whether the strategy ended above it.
     */
    public record Run(
            String market,
            String source,
            String product,
            long granularitySeconds,
            Long missingBars,
            BacktestReport report,
            String benchmark,
            BacktestResult benchmarkResult,
            Boolean beatsBenchmark) {}
}
