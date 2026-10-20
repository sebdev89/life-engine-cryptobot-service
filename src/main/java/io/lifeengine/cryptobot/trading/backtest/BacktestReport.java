package io.lifeengine.cryptobot.trading.backtest;

import java.math.BigDecimal;
import java.util.List;

/**
 * A strategy next to the two benchmarks it has to beat over the same bars and costs. {@code beatsDoingNothing}
 * is the v3 gate in miniature: the strategy ends with more USD than both holding SOL and holding USDC. A
 * backtest that passes is a reason to paper-trade, never a reason to put capital in.
 */
public record BacktestReport(
        BacktestResult strategy,
        BacktestResult holdSol,
        BacktestResult holdUsdc,
        boolean beatsHoldSol,
        boolean beatsHoldUsdc,
        boolean beatsDoingNothing,
        FeeModel fees) {

    public static BacktestReport of(List<PriceBar> bars, BacktestStrategy strategy, BigDecimal initialUsd, FeeModel fees) {
        BacktestResult s = Backtester.run(bars, strategy, initialUsd, fees);
        BacktestResult sol = Backtester.run(bars, Strategies.holdSol(), initialUsd, fees);
        BacktestResult usdc = Backtester.run(bars, Strategies.holdUsdc(), initialUsd, fees);
        boolean beatsSol = s.finalUsd().compareTo(sol.finalUsd()) > 0;
        boolean beatsUsdc = s.finalUsd().compareTo(usdc.finalUsd()) > 0;
        return new BacktestReport(s, sol, usdc, beatsSol, beatsUsdc, beatsSol && beatsUsdc, fees);
    }
}
