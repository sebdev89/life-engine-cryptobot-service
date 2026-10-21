package io.lifeengine.cryptobot.trading.backtest;

import io.lifeengine.cryptobot.trading.backtest.BacktestStrategy.Holdings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Replays a SOL/USD close series through a {@link BacktestStrategy} and measures it net of costs (KAN-1216,
 * CryptoBot v2). Deterministic by construction: BigDecimal arithmetic with a fixed MathContext, SOL
 * truncated to lamports (9 decimals) and USDC to its 6 decimals, rounding always against the strategy. Same
 * bars, strategy and fees → byte-identical result.
 *
 * <p>The capital is allocated for free at the first close (see {@link FeeModel}); then, at every close (the
 * first included), the strategy decides and the trade fills per {@link Execution}, the same model paper trading
 * uses. Equity is marked at each close after the trade and measured by {@link Performance}.
 */
public final class Backtester {

    private Backtester() {}

    public static BacktestResult run(List<PriceBar> bars, BacktestStrategy strategy, BigDecimal initialUsd, FeeModel fees) {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(fees, "fees");
        if (initialUsd == null || initialUsd.signum() <= 0) {
            throw new IllegalArgumentException("initialUsd must be positive: " + initialUsd);
        }
        if (bars == null || bars.size() < 2) {
            throw new IllegalArgumentException("a backtest needs at least 2 bars");
        }
        for (int i = 1; i < bars.size(); i++) {
            if (!bars.get(i).time().isAfter(bars.get(i - 1).time())) {
                throw new IllegalArgumentException("bars must be strictly increasing in time (index " + i + ")");
            }
        }

        Holdings h = Execution.allocate(initialUsd, strategy.initialSolWeight(), bars.get(0).close());
        BigDecimal capital = initialUsd.setScale(Execution.USD_SCALE, RoundingMode.DOWN);
        BigDecimal costs = BigDecimal.ZERO;
        int trades = 0;
        List<Performance.Mark> marks = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            PriceBar bar = bars.get(i);
            Execution.Fill fill = Execution.fill(h, strategy.decide(i, bar, h), bar.close(), fees);
            h = fill.after();
            costs = costs.add(fill.costUsd());
            trades += fill.traded() ? 1 : 0;
            marks.add(mark(h, bar.close()));
        }
        return Performance.measure(strategy.id(), bars, capital, marks, trades, costs, h);
    }

    /** Equity after the trade and the share of it in SOL (0 when the equity is 0). */
    public static Performance.Mark mark(Holdings h, BigDecimal close) {
        BigDecimal equity = Execution.equity(h, close);
        BigDecimal weight = equity.signum() > 0 ? h.sol().multiply(close).divide(equity, Execution.MC) : BigDecimal.ZERO;
        return new Performance.Mark(equity, weight);
    }
}
