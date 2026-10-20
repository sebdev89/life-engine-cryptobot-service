package io.lifeengine.cryptobot.application.backtest;

import io.lifeengine.cryptobot.adapters.marketdata.CoinbaseCandleHistory;
import io.lifeengine.cryptobot.trading.backtest.BacktestReport;
import io.lifeengine.cryptobot.trading.backtest.BacktestStrategy;
import io.lifeengine.cryptobot.trading.backtest.FeeModel;
import io.lifeengine.cryptobot.trading.backtest.Strategies;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Runs one backtest over public SOL/USD history (KAN-1216, CryptoBot v2): fetches the closes, builds the
 * strategy from the request and compares it against holding SOL and holding USDC. Read-only — it never touches
 * a wallet, a signer or the chain; a result is evidence for paper trading (v3), never an order.
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);
    public static final String SOURCE = "coinbase-exchange-candles";

    private final CoinbaseCandleHistory history;
    private final BacktestProperties properties;

    public BacktestService(CoinbaseCandleHistory history, BacktestProperties properties) {
        this.history = history;
        this.properties = properties;
    }

    public Mono<Run> run(Request request) {
        BacktestStrategy strategy;
        FeeModel fees;
        try {
            strategy = strategyOf(request);
            fees = new FeeModel(
                    request.feeBps() == null ? FeeModel.DEFAULT.feeBps() : request.feeBps(),
                    request.slippageBps() == null ? FeeModel.DEFAULT.slippageBps() : request.slippageBps());
            if (request.from() == null || request.to() == null) {
                throw new IllegalArgumentException("from and to are required");
            }
        } catch (IllegalArgumentException ex) {
            return Mono.error(ex);
        }
        BigDecimal initialUsd = request.initialUsd() == null ? new BigDecimal("1000") : request.initialUsd();
        Duration granularity = Duration.ofSeconds(request.granularitySeconds() == null ? 3_600 : request.granularitySeconds());
        String product = properties.product();
        return history.closes(product, granularity, request.from(), request.to())
                .map(bars -> {
                    BacktestReport report = BacktestReport.of(bars, strategy, initialUsd, fees);
                    log.info("backtest_run strategy={} bars={} from={} to={} return_pct={} beats_doing_nothing={}",
                            strategy.id(), bars.size(), request.from(), request.to(),
                            report.strategy().returnPct(), report.beatsDoingNothing());
                    long expected = Duration.between(request.from(), request.to()).toSeconds() / granularity.toSeconds();
                    return new Run(SOURCE, product, granularity.toSeconds(), Math.max(0, expected - bars.size()), report);
                });
    }

    static BacktestStrategy strategyOf(Request r) {
        return Strategies.named(r.strategy(), r.targetSolWeight(), r.bandBps(), r.dcaAmountUsd(), r.dcaEveryBars());
    }

    /** Optional fields default to: 1000 USD, 1 h bars, Jupiter-like costs (30 + 20 bps), band 50 % ± 5 %. */
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
            Integer dcaEveryBars) {}

    /**
     * The report plus where its prices came from, so a result can be reproduced. {@code missingBars}: candles the
     * source does not have inside the window (Coinbase skips intervals without trades; 5 of 4320 hourly bars
     * over 180 days of SOL-USD on 2026-10-20). The backtest runs over what exists, never over invented prices.
     */
    public record Run(String source, String product, long granularitySeconds, long missingBars, BacktestReport report) {}
}
