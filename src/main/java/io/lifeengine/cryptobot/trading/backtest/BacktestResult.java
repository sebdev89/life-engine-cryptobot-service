package io.lifeengine.cryptobot.trading.backtest;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What one strategy did over one price series, net of every cost. {@code returnPct} and
 * {@code maxDrawdownPct} are percentages (12.5 = 12.5 %); {@code sharpe} is annualized from the per-bar
 * returns with a zero risk-free rate; {@code costsUsd} is fees plus slippage paid on all trades.
 */
public record BacktestResult(
        String strategyId,
        Instant from,
        Instant to,
        int bars,
        BigDecimal initialUsd,
        BigDecimal finalUsd,
        BigDecimal pnlUsd,
        BigDecimal returnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpe,
        int trades,
        BigDecimal costsUsd,
        BigDecimal finalSol,
        BigDecimal finalUsdc) {}
